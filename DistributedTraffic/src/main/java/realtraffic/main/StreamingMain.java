package realtraffic.main;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.Optional;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import com.bmwcarit.barefoot.markov.KState;
import com.bmwcarit.barefoot.matcher.Matcher;
import com.bmwcarit.barefoot.matcher.MatcherCandidate;
import com.bmwcarit.barefoot.matcher.MatcherSample;
import com.bmwcarit.barefoot.matcher.MatcherTransition;
import com.bmwcarit.barefoot.util.Tuple;
import com.esri.core.geometry.Point;

import realtraffic.common.Common;
import realtraffic.gps.Sample;
import realtraffic.road.RoadConstant;
import scala.Tuple2;
import scala.Tuple6;
import scala.Tuple8;
public class StreamingMain {
	
	public static void main(String[] args) throws InterruptedException, FileNotFoundException{
		PrintStream ps=new PrintStream(new FileOutputStream("/home/qhyue/CarCount.txt"));
		System.setOut(ps);

		Common.init(40000);//initialize map, matchers
		//Common.init_roadlist();//initialize roadmap and initial state
		
		SparkConf conf = new SparkConf().setAppName("carCount").setMaster("local[2]");
		// 该对象除了接受SparkConf对象,还要接受一个Batch Interval参数,就是说,每收集多长时间数据划分一个batch去进行处理
		// Durations里面可以设置分钟、毫秒、秒,这里设置1秒
		JavaStreamingContext jssc = new JavaStreamingContext(conf,Durations.seconds(1));
		
		// 首先创建输入DStream,代表一个数据源比如从socket或kafka来持续不断的进入实时数据流
		// 创建一个监听端口的socket数据流,这里面就会有每隔一秒生成一个RDD,RDD的元素类型为String就是一行一行的文本
		JavaReceiverInputDStream<String> lines = jssc.socketTextStream("localhost", 9999);
		jssc.checkpoint("/mnt/freenas/cheakpoint");

		//地图匹配器对象-->广播变量
		final Broadcast<Matcher> matcher = jssc.sparkContext().broadcast(Common.matcher);
		//固定不变的道路信息-->g广播变量
		//final Broadcast<HashMap<Integer, RoadConstant>> RoadConstant =  jssc.sparkContext().broadcast(Common.roadConstant);
		//从这里分开？可以分成多个Dstream
		//每秒的GPS信息
		JavaDStream<Sample> gps = lines.map(new Function<String,Sample>(){
			private static final long serialVersionUID = 1L;
			@Override
			public Sample call(String v1) throws Exception {
				String date = v1.split(" ")[0];
				long suid = Long.parseLong(v1.split(" ")[1]);
				long utc = Long.parseLong(v1.split(" ")[2]);
				long lat = Long.parseLong(v1.split(" ")[3]);
				long lon = Long.parseLong(v1.split(" ")[4]);
				int head = (int) Long.parseLong(v1.split(" ")[5]);
				boolean passenger = Boolean.parseBoolean(v1.split(" ")[6]);
				Sample gps = new Sample(date, suid, utc, lat, lon, head, passenger);
				//gps.x_idx = (int) Long.parseLong(v1.split(" ")[7]);
				//gps.y_idx = (int) Long.parseLong(v1.split(" ")[8]);
				return gps;
				/*if(suid%3==0)
				{
					return gps;
				}
				else return null;*/
			}
		})/*.filter(new Function<Sample,Boolean> (){
			private static final long serialVersionUID = 1L;
			@Override
			public Boolean call(Sample v1) throws Exception {
				// TODO Auto-generated method stub
				return v1!=null;
			}
		})*/;
		//按<suid, sample>返回,可能有一秒内有状态改变的车，会出现两次
		JavaPairDStream<Long, Sample> gpsPairs = gps.mapToPair(new PairFunction<Sample, Long, Sample>(){
			private static final long serialVersionUID = 1L;
			@Override
			public Tuple2<Long, Sample> call(Sample sample) throws Exception {
				return new Tuple2<Long, Sample>(sample.suid, sample);
			}
		});

		/*不去重了
		//去重 这样每秒只有一个suid一样的车辆，<suid, sample>
		JavaPairDStream<Long, Sample> gpspair =  gpsPairs.reduceByKey(new Function2<Sample, Sample, Sample>(){
			private static final long serialVersionUID = 1L;
			@Override
			public Sample call(Sample v1, Sample v2) throws Exception {
				return v1;
			}
		});
		*/
		
		/**
		 * 延迟修正的算法，state序列化有点问题
		 * 
		 * 效率随着键的增加逐渐变慢，因为涉及shuffle，它是针对整个RDD来进行cogroup，所以如果要提前分开，可以分成多个Dstream
		 * 
		 * 由于updateStateByKey内部的cogroup会对所有数据进行扫描，再按key进行分组(目前北京市一天有2.8W辆出租车(key))，
		 * 所以计算速度会与key的数量相关。
		 * */
		//道路匹配过程：
		//Long:suid
		//tuple：第几个点，前一个原始点pre_sample，状态，时间序列，日期序列，乘客序列，前一个转换后的车，当前转换后的车
		
		JavaPairDStream<Long, Tuple8<Integer,Sample,KState<MatcherCandidate, MatcherTransition, MatcherSample>, HashMap<Integer,Long>, HashMap<Integer,String>, HashMap<Integer,Boolean>, Sample,Sample>>  gpsMatcher = gpsPairs.updateStateByKey(
				new Function2<List<Sample>, Optional<Tuple8<Integer,Sample,KState<MatcherCandidate, MatcherTransition, MatcherSample>, HashMap<Integer,Long>,HashMap<Integer,String>,HashMap<Integer,Boolean>, Sample,Sample>>, Optional<Tuple8<Integer,Sample,KState<MatcherCandidate, MatcherTransition, MatcherSample>, HashMap<Integer,Long>,HashMap<Integer,String>, HashMap<Integer,Boolean>, Sample,Sample>>>(){
					private static final long serialVersionUID = -4619756576020847188L;

			// 这里的Optional,相当于scala中的样例类,就是Option,可以理解它代表一个状态,可能之前存在,也可能之前不存在
			// 实际上,对于每个gps的suid,每次batch计算的时候,都会调用这个函数
			//第一个参数,sampleList相当于这个batch中,这个key的新的值,可能有多个,
			//比如这个batch中有2个hello,(hello,1) (hello,1) 那么传入的是(1,1)
					
			// 第二个参数oldState表示的是这个key之前的状态,这个泛型的参数是我们指定的
			@Override
			public Optional<Tuple8<Integer,Sample,KState<MatcherCandidate, MatcherTransition, MatcherSample>, HashMap<Integer,Long>, HashMap<Integer,String>, HashMap<Integer,Boolean>, Sample,Sample>> call(
					List<Sample> sampleList,
					Optional<Tuple8<Integer,Sample,KState<MatcherCandidate, MatcherTransition, MatcherSample>, HashMap<Integer,Long>, HashMap<Integer,String>, HashMap<Integer,Boolean>, Sample,Sample>> oldState)
					throws Exception {
				//ArrayList<Sample> taxi_queue = null;//原始出租车队列暂时没用,转化后的出租车队列有用
				//近似算法还没有加入
				//Boolean is_dump = false;
				//Integer dump_x_idx = -1;
				//Integer dump_y_idx = -1;
				
				int sample_id = 0;//初始化
				Sample pre_sample = null;//last gps point,for preprocessing
				//Sample sample=null;//初始化
				Sample pre_converge_sample = null;//初始化
				Sample converge_sample = null;//初始化
				KState<MatcherCandidate, MatcherTransition, MatcherSample> state = null;//初始化

				HashMap<Integer,Long> time_map = null;//record map from sample_id to utc
				HashMap<Integer,String> date_map = null;//record map from sample_id to date
				HashMap<Integer,Boolean> passenger_map = null;//record map from sample_id to passenager
				
				if(oldState.isPresent()){
					sample_id = oldState.get()._1();
					pre_sample = oldState.get()._2();
					state = oldState.get()._3();
					time_map = oldState.get()._4();
					date_map = oldState.get()._5();
					passenger_map = oldState.get()._6();
					System.out.println("oldstate sequence "+state.sequence.size()+"state.size "+state.counters.size());	
					//pre_converge_sample = oldState.get()._8();
				}//初始化
				else{
					//taxi_queue = new ArrayList<Sample>();
					state = new KState<MatcherCandidate, MatcherTransition, MatcherSample>(6, -1);
					//初始化,这里6代表的是窗口内最多7个
					time_map = new HashMap<Integer,Long>();
					date_map = new HashMap<Integer,String>();
					passenger_map = new HashMap<Integer,Boolean>();
				}	
				//当前batch有这辆车sample,如果符合预处理条件，则sample_id ++;
				//当前原始车辆sample
				//有时间先后顺序的

				for(int i=0; i< sampleList.size(); i++){
					Sample sample = sampleList.get(i);
					//preprocess
					if(!preprocess(pre_sample,sample/*,is_dump,dump_x_idx,dump_y_idx*/)){
						continue;
					}
					pre_sample = sample;
					//System.out.println("sample_id "+sample_id);
					MatcherSample matcher_sample = new MatcherSample(String.valueOf(sample_id), 
							sample.utc.getTime(), new Point(sample.lon, sample.lat));
					time_map.put(sample_id, sample.utc.getTime()/1000);
					date_map.put(sample_id, sample.date);
					passenger_map.put(sample_id, sample.passenger);
					sample_id++;
					//this function cost most of time
					Set<MatcherCandidate> stateVector = state.vector();
					/*for(MatcherCandidate i1 : stateVector)
					{
						System.out.println("matching_id "+i1.matching_id());
					}*/
					MatcherSample stateSample = state.sample();
					Set<MatcherCandidate> vector = matcher.value().execute(stateVector, stateSample,
				    		matcher_sample);
					System.out.println(state.k+" "+state.t+" "+"vectorhou sequence "+state.sequence.size()+"state.size "+state.counters.size());
					System.out.println("update vector "+vector.size());
					//convergency point or top point if windows size exceed thresold or null
					MatcherCandidate converge = state.update_converge(vector, matcher_sample);
					System.out.println(state.k+" "+state.t+" "+"convergehou sequence "+state.sequence.size()+"state.size "+state.counters.size());
					
					// test whether the point is unable to match
				    MatcherCandidate estimate = state.estimate(); // most likely position estimate

				    /*if(estimate == null || estimate.point() == null){
				    	continue;
				    }
				    //unconvergency
					if(converge == null){
						continue;
					}
					int id = Integer.parseInt(converge.matching_id());
					long utc = time_map.remove(id);
					System.out.println("suid "+sample.suid+" sample_id "+sample_id+" id "+ id + " utc " + utc);
					boolean passenager = passenger_map.remove(id);
					String date = date_map.remove(id);
					Point position = converge.point().geometry(); // position
					
					converge_sample = new Sample(date, sample.suid, utc, position.getY(), 
							position.getX(), 0, passenager);
					converge_sample.gid = (int)converge.point().edge().id(); // road id
				    converge_sample.offset = converge.point().fraction();
				    
				    if(converge.transition() != null ){
				    	converge_sample.route = converge.transition().route().toString(); // route to position
				    }
				    System.out.println(converge_sample.suid+" "+ converge_sample.utc.getTime()/1000 +" "+ converge_sample.lat + " " + converge_sample.lon + " "+ converge_sample.gid);
				*/
				}
				System.out.println(state.k+" "+state.t+" "+"forhou sequence "+state.sequence.size()+"state.size "+state.counters.size());

				return Optional.of(new Tuple8<>(sample_id,pre_sample,state, time_map, date_map,passenger_map,pre_converge_sample,converge_sample));			
			}
		});
		
		//匹配后的gps,涵盖了道路信息,即<converge_sample,这辆车出现的次数(第几次)>
		//pre_converge_sample,converge_sample
		
		JavaPairDStream<Sample, Sample> MatcheredGPS = gpsMatcher.mapToPair(new PairFunction<Tuple2<Long, Tuple8<Integer,Sample,KState<MatcherCandidate, MatcherTransition, MatcherSample>, HashMap<Integer,Long>, HashMap<Integer,String>, HashMap<Integer,Boolean>, Sample,Sample>>, Sample, Sample>(){
			private static final long serialVersionUID = 1L;
			@Override
			public Tuple2<Sample, Sample> call(
					Tuple2<Long, Tuple8<Integer, Sample, KState<MatcherCandidate, MatcherTransition, MatcherSample>, HashMap<Integer, Long>, HashMap<Integer, String>, HashMap<Integer, Boolean>, Sample, Sample>> t)
					throws Exception {
				//转化后的车辆converge_sample不为空
				/*if(t!=null && t._2!=null && t._2._8()!=null)
				{
					//返回pre_converge_sample,converge_sample
					return new Tuple2<Sample, Sample>(t._2._7(), t._2._8());
				}
				else*/ return null;
			}
		});
		/*
		// 最后每次计算完,都打印一下这1秒钟的gps信息
		gps.foreachRDD(new VoidFunction<JavaRDD<Sample>>(){
			private static final long serialVersionUID = 1L;
			@Override
			public void call(JavaRDD<Sample> t) throws Exception {
				for(Sample s : t.collect())
				{
					System.out.println("before:"+s.suid+" "+ s.utc.getTime()/1000 +" "+ s.lat + " " + s.lon);
				}
				//每秒进来的车辆数据
				System.out.println("======================");
			}
		});*/
		//先分成3部分看看
		//JavaPairDStream<Sample, Integer> MatcheredGPSUnion = MatcheredGPS.union(MatcheredGPS2).union(MatcheredGPS3);
		/*MatcheredGPS.foreachRDD(new VoidFunction<JavaPairRDD<Sample,Integer>>(){
			private static final long serialVersionUID = 1L;
			@Override
			public void call(JavaPairRDD<Sample, Integer> t) throws Exception {
				//System.out.println("t.size:"+t.partitions().size());13片，和线程数量有关
				//如果不collect应该更好，collect花费的时间长
				for(Tuple2<Sample, Integer> s : t.collect())
				{
					if(s!=null && s._1!=null && s._2>=0)
					{
						System.out.println("after:"+s._1.suid+" "+ s._1.utc.getTime()/1000 +" "+ s._1.lat + " " + s._1.lon + " "+ s._1.gid);	
					}
				}
				SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");	
				String time = tempDate.format(new java.util.Date());
				//每秒进来的车辆数据
				System.out.println("1time："+time);
			}
		});*/
		/*
		//action算子触发执行
		MatcheredGPS.foreachRDD(new VoidFunction<JavaPairRDD<Sample,Sample>>(){
			private static final long serialVersionUID = 1L;
			@Override
			public void call(JavaPairRDD<Sample, Sample> t) throws Exception {
				t.foreachPartition(new VoidFunction<Iterator<Tuple2<Sample,Sample>>>(){
					private static final long serialVersionUID = 1L;
					@Override
					public void call(Iterator<Tuple2<Sample, Sample>> it) throws Exception {
						while(it.hasNext())
						{
							Tuple2<Sample, Sample> s=it.next();
							if(s!=null && s._2!=null)
							{
								System.out.println("after:"+s._2.suid+" "+ s._2.utc.getTime()/1000 +" "+ s._2.lat + " " + s._2.lon + " "+ s._2.gid);	
							}
						}
					}
				});
				SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");	
				String time = tempDate.format(new java.util.Date());
				System.out.println("time："+time);
			}	
		});
		*/
		/*JavaDStream<Sample> GPS = MatcheredGPS.mapPartitions(new FlatMapFunction<Iterator<Tuple2<Sample,Integer>>,Sample>(){
			private static final long serialVersionUID = 1L;

			@Override
			public Iterator<Sample> call(Iterator<Tuple2<Sample, Integer>> t) throws Exception {
				while(t.hasNext())
				{
					Tuple2<Sample, Integer> s=t.next();
					System.out.println("after:"+s._1.suid+" "+ s._1.utc.getTime()/1000 +" "+ s._1.lat + " " + s._1.lon + " "+ s._1.gid);
				}
				SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");	
				String time = tempDate.format(new java.util.Date());
				//每秒进来的车辆数据
				System.out.println("1time："+time);
				return null;
			}
		});*/
		//action算子触发执行
		/*GPS.foreachRDD(new VoidFunction<JavaRDD<Sample>>(){
			private static final long serialVersionUID = 1L;
			@Override
			public void call(JavaRDD<Sample> t) throws Exception {
				// TODO Auto-generated method stub
			}
		});*/
		
		MatcheredGPS.foreachRDD(new VoidFunction<JavaPairRDD<Sample,Sample>>(){
			private static final long serialVersionUID = 1L;
			@Override
			public void call(JavaPairRDD<Sample, Sample> t) throws Exception {
				//System.out.println("t.size:"+t.partitions().size());13片，和线程数量有关
				//如果不collect应该更好，collect花费的时间长
				/*for(Tuple2<Sample, Sample> s : t.collect())
				{
					if(s!=null && s._2!=null)
					{
						System.out.println("after:"+s._2.suid+" "+ s._2.utc.getTime()/1000 +" "+ s._2.lat + " " + s._2.lon + " "+ s._2.gid);	
					}
				}*/
				
				t.collect();
				//SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");	
				//String time = tempDate.format(new java.util.Date());
				//每秒进来的车辆数据
				//System.out.println("time："+time);
			}
		});

		
		// 必须调用start方法,整个spark streaming应用才会启动执行,然后卡在那里,最后close释放资源
		jssc.start();
		jssc.awaitTermination();
		jssc.close();
	}
	
	public static Tuple6<Integer,HashMap<Integer,Long>,HashMap<Integer,String>,HashMap<Integer,Boolean>,KState<MatcherCandidate, MatcherTransition, MatcherSample>,Sample> realtime_match(Broadcast<Matcher> matcher,Sample sample,Integer sample_id,HashMap<Integer,Long> time_map,HashMap<Integer,String> date_map,
			HashMap<Integer,Boolean> passenger_map,KState<MatcherCandidate, MatcherTransition, MatcherSample> state){
		try{
			MatcherSample matcher_sample = new MatcherSample(String.valueOf(sample_id), 
					sample.utc.getTime(), new Point(sample.lon, sample.lat));
			time_map.put(sample_id, sample.utc.getTime()/1000);
			date_map.put(sample_id, sample.date);
			passenger_map.put(sample_id, sample.passenger);
			sample_id++;
			
			//this function cost most of time
			Set<MatcherCandidate> vector = matcher.value().execute(state.vector(), state.sample(),matcher_sample);
			//convergency point or top point if windows size exceed thresold or null
			MatcherCandidate converge = state.update_converge(vector, matcher_sample);
		    // test whether the point is unable to match
		    MatcherCandidate estimate = state.estimate(); // most likely position estimate
		    if(estimate == null || estimate.point() == null){
		    	return new Tuple6<>(sample_id,time_map,date_map,passenger_map,state,null);
		    }
		    //unconvergency
			if(converge == null){
				return new Tuple6<>(sample_id,time_map,date_map,passenger_map,state,null);
			}
			int id = Integer.parseInt(converge.matching_id());
			long utc = time_map.remove(id);
			System.out.println("suid "+sample.suid+" sample_id "+sample_id+" id "+ id + " utc " + utc);
			boolean passenger = passenger_map.remove(id);
			String date = date_map.remove(id);
			Point position = converge.point().geometry(); // position
			
			Sample converge_sample = new Sample(date, sample.suid, utc, position.getY(), 
					position.getX(), 0, passenger);
			converge_sample.gid = (int)converge.point().edge().id(); // road id
		    converge_sample.offset = converge.point().fraction();
		    
		    if(converge.transition() != null ){
		    	converge_sample.route = converge.transition().route().toString(); // route to position
		    }
		   // System.out.println(converge_sample.suid+" "+ converge_sample.utc.getTime()/1000 +" "+ converge_sample.lat + " " + converge_sample.lon + " "+ converge_sample.gid);
			
		    return new Tuple6<>(sample_id,time_map,date_map,passenger_map,state,converge_sample);
		}
		catch(Exception e){
		    e.printStackTrace();			
		    return new Tuple6<>(sample_id,time_map,date_map,passenger_map,state,null);
		}
	}
	//preprocess of one gps point, 
	public static boolean preprocess(Sample pre_sample,Sample sample/*,boolean is_dump,int dump_x_idx,int dump_y_idx*/){
		if(pre_sample == null){
			return true;
		}
		//Date of point is previous to last point 
		if (!sample.utc.after(pre_sample.utc)){
			return false;
		}
		//point in queue will be discarded and state will be initialized
		long interval = sample.utc.getTime()/1000 - pre_sample.utc.getTime()/1000;
		//间隔小于10s
		if(interval < 10){
			return false;
		}
		/*
		//dump points
		if(is_dump){
			if(interval > Common.MAX_GPS_INTERVAL || sample.passenager != pre_sample.passenager){
				is_dump = false;
				return true;
			}
			//taxi has leaved the area
			if(sample.x_idx != dump_x_idx || sample.y_idx != dump_y_idx){
				is_dump = false;
				return true;
			}
			Common.dump_number++;
			if(sample.date.equals("_2010_04_14")){
				Common.dump_number_0414++;
			}
			return false;
		}
		*/
		return true;
	}
}
