package realtraffic.main;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.Lock;

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
import org.apache.spark.streaming.Time;
import com.bmwcarit.barefoot.markov.KState;
import com.bmwcarit.barefoot.matcher.Matcher;
import com.bmwcarit.barefoot.matcher.MatcherCandidate;
import com.bmwcarit.barefoot.matcher.MatcherSample;
import com.bmwcarit.barefoot.matcher.MatcherTransition;
import com.bmwcarit.barefoot.road.PostGISReader;
import com.bmwcarit.barefoot.roadmap.Road;
import com.bmwcarit.barefoot.roadmap.RoadMap;
import com.bmwcarit.barefoot.roadmap.RoadPoint;
import com.bmwcarit.barefoot.roadmap.TimePriority;
import com.bmwcarit.barefoot.spatial.Geography;
import com.bmwcarit.barefoot.topology.Dijkstra;
import com.bmwcarit.barefoot.util.Tuple;
import com.esri.core.geometry.Point;

import SendData.SendGpsData;
import realtraffic.common.Common;
import realtraffic.gps.Sample;
import realtraffic.process.TaxiInfo;
import realtraffic.road.AllocationRoadsegment;
import realtraffic.road.RoadConstant;
import scala.Tuple2;
import scala.Tuple3;
import scala.Tuple4;
import scala.Tuple5;
import scala.Tuple6;
import scala.Tuple8;
public class StreamingMainTaxiInfo extends Thread{
	public static ArrayList<String> Line = new ArrayList<String>();

	public class InnerClass extends Thread{
		private PrintStream out;
	    public InnerClass(OutputStream output) {
	    	this.out = new PrintStream(output,true);
	    }
	    public void run(){
	    	while(true)
	    	{
	    		if(Line.size()>0)
		    	{
		    		for(String line :Line)
			    	{
			    		out.println(line);
			    	}
		    		//Line.clear();
		    	}
	    		synchronized (Lock.class)
	            {
	                try {
	                    Lock.class.wait();
	                } catch (InterruptedException e) {
	                    e.printStackTrace();
	                }
	            }
	    	}
	    	
	    }
	}

    public static void main(String[] args) throws InterruptedException, IOException{
    	

	    PrintStream ps=new PrintStream(new FileOutputStream("/home/qhyue/CarCount.txt"));
		System.setOut(ps);

		Map<Short, Tuple<Double, Integer>> road_config = new HashMap<Short, Tuple<Double, Integer>>();
		short class_id[] = {100,101,102,104,105,106,107,108,109,110,111,112,113,114,
				117,118,119,120,122,123,124,125,201,202,301,303,304,305};
		double priority[] = {1.30,1.0,1.10,1.04,1.12,1.08,1.15,1.10,1.20,1.12,1.25,1.30,
				1.50,1.75,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30};
		for(int i=0; i<28; i++){
			road_config.put(class_id[i], new Tuple<Double, Integer>(priority[i], (int)Common.max_speed));
		}
		final RoadMap map = RoadMap.Load(new PostGISReader(Common.Host, Common.Port, 
				Common.DataBase, Common.OriginWayTable, Common.UserName, Common.UserPwd, road_config));
		map.construct();
		Matcher matcher = new Matcher(map, new Dijkstra<Road, RoadPoint>(),
	            new TimePriority(), new Geography());
		
		SparkConf conf = new SparkConf().setAppName("CarMatch").setMaster("local[10]");
		// 该对象除了接受SparkConf对象,还要接受一个Batch Interval参数,就是说,每收集多长时间数据划分一个batch去进行处理
		// Durations里面可以设置分钟、毫秒、秒,这里设置1秒
		final JavaStreamingContext jssc = new JavaStreamingContext(conf,Durations.seconds(10));
		JobListener job = new JobListener();
		jssc.addStreamingListener(job);
		// 首先创建输入DStream,代表一个数据源比如从socket或kafka来持续不断的进入实时数据流
		// 创建一个监听端口的socket数据流,这里面就会有每隔一秒生成一个RDD,RDD的元素类型为String就是一行一行的文本
		JavaReceiverInputDStream<String> lines = jssc.socketTextStream("localhost", 9999);
		jssc.checkpoint("/mnt/freenas/cheakpoint");

		//地图匹配器对象-->广播变量
		final Broadcast<Matcher> match = jssc.sparkContext().broadcast(matcher);
		
		final Broadcast<HashMap<Long, Road>> roadMap= jssc.sparkContext().broadcast(map.edges);
		HashMap<Long, Tuple2<Road,Road>> SuccessorAndNeighbor = new HashMap<Long, Tuple2<Road,Road>>();
		Set<Long> keySet = map.edges.keySet();
        for(Long i : keySet)
        {
        	SuccessorAndNeighbor.put(i, new Tuple2<>(map.edges.get(i).successor,map.edges.get(i).neighbor));
        }
        final Broadcast<HashMap<Long, Tuple2<Road, Road>>> successorAndNeighbor= jssc.sparkContext().broadcast(SuccessorAndNeighbor);
        
		// Create the queue through which RDDs can be pushed to a QueueInputDStream
		//final Queue<JavaRDD<Integer>> roadQueue = new LinkedList<JavaRDD<Integer>>();
		//final JavaRDD<Integer> roadList = jssc.sparkContext().parallelize(Common.roadArrayList);
		//roadQueue.add(roadList);
		//要更新的道路表DStream
	    //JavaDStream<Integer> RoadStream = jssc.queueStream(roadQueue); 

	    /*RoadStream.foreachRDD(new VoidFunction<JavaRDD<Integer>>(){

			private static final long serialVersionUID = 1L;

			@Override
			public void call(JavaRDD<Integer> t) throws Exception {
				// TODO Auto-generated method stub
				for(int i =0;i<t.collect().size();i++)
				{
					System.out.println("i"+t.collect().get(i));
				}
			}
	    	
	    });*/
	    
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
		
		/**
		 * 在线的道路匹配，延迟修正的算法,正确性通过
		 * 
		 * 效率随着键的增加逐渐变慢，因为涉及shuffle，它是针对整个RDD来进行cogroup，所以如果要提前分开，可以分成多个Dstream
		 * 
		 * 由于updateStateByKey内部的cogroup会对所有数据进行扫描，再按key进行分组(目前北京市一天有2.8W辆出租车(key))，
		 * 所以计算速度会与key的数量相关。
		 */
		//道路匹配过程：
		//Long:suid
		//tuple：第几个点，前一个原始点pre_sample，状态，时间序列，日期序列，乘客序列，前一个转换后的车preConvergeList，当前转换后的车convergeList
		
		JavaPairDStream<Long, Tuple5<TaxiInfo,HashMap<Road,Road>,HashMap<Road,Road>,ArrayList<Sample>,ArrayList<Sample>>>  gpsMatcher = gpsPairs.updateStateByKey(
				new Function2<List<Sample>, Optional<Tuple5<TaxiInfo,HashMap<Road,Road>,HashMap<Road,Road>,ArrayList<Sample>,ArrayList<Sample>>>, Optional<Tuple5<TaxiInfo,HashMap<Road,Road>,HashMap<Road,Road>,ArrayList<Sample>,ArrayList<Sample>>>>(){
			// 这里的Optional,相当于scala中的样例类,就是Option,可以理解它代表一个状态,可能之前存在,也可能之前不存在
			// 实际上,对于每个gps的suid,每次batch计算的时候,都会调用这个函数
			//第一个参数,sampleList相当于这个batch中,这个key的新的值,可能有多个,
			//比如这个batch中有2个hello,(hello,1) (hello,1) 那么传入的是(1,1)
			private static final long serialVersionUID = 1L;

			// 第二个参数oldState表示的是这个key之前的状态,这个泛型的参数是我们指定的
			@Override
			public Optional<Tuple5<TaxiInfo,HashMap<Road,Road>,HashMap<Road,Road>,ArrayList<Sample>,ArrayList<Sample>>> call(
					List<Sample> sampleList,
					Optional<Tuple5<TaxiInfo,HashMap<Road,Road>,HashMap<Road,Road>,ArrayList<Sample>,ArrayList<Sample>>> oldState)
					throws Exception {
				
				TaxiInfo taxi = null;
				HashMap<Road,Road> Road_Successor = null;
				HashMap<Road,Road> Road_Neighbor = null;
				if(oldState.isPresent()){
					taxi=oldState.get()._1();
					Road_Successor = oldState.get()._2();
					Road_Neighbor = oldState.get()._3();
					
				}
				else{
					Sample sample = sampleList.get(0);
					taxi=new TaxiInfo(sample.suid);
				}
				
				//当前batch有这辆车sample,如果符合预处理条件，则sample_id ++;
				//当前原始车辆sample
				//有时间先后顺序的
				ArrayList<Sample> preConvergeList = new ArrayList<Sample>();
				ArrayList<Sample> convergeList = new ArrayList<Sample>();
				
				//自己写保存恢复
				for(MatcherCandidate i0 : taxi.state.vector())
				{
					if(Road_Successor.containsKey(i0.point().edge()))
					{
						i0.point().edge().successor = Road_Successor.get(i0.point().edge());
					}
					if(Road_Neighbor.containsKey(i0.point().edge()))
					{
						i0.point().edge().neighbor = Road_Neighbor.get(i0.point().edge());
					}
				}
				
				for(int i=0; i< sampleList.size(); i++){
					Sample sample = sampleList.get(i);
					//preprocess
					if(!preprocess(taxi.pre_sample,sample/*,is_dump,dump_x_idx,dump_y_idx*/)){
						continue;
					}
					taxi.pre_sample = sample;
					MatcherSample matcher_sample = new MatcherSample(String.valueOf(taxi.sample_id), 
							sample.utc.getTime(), new Point(sample.lon, sample.lat));
					taxi.time_map.put(taxi.sample_id, sample.utc.getTime()/1000);
					taxi.date_map.put(taxi.sample_id, sample.date);
					taxi.passenger_map.put(taxi.sample_id, sample.passenger);
					taxi.sample_id++;
					Set<MatcherCandidate> stateVector = taxi.state.vector();
					//this function cost most of time
					MatcherSample stateSample = taxi.state.sample();
					Set<MatcherCandidate> vector = match.value().execute(successorAndNeighbor.value(), stateVector, stateSample,
				    		matcher_sample);
					//convergency point or top point if windows size exceed thresold or null
					MatcherCandidate converge = taxi.state.update_converge(vector, matcher_sample);
					
					// test whether the point is unable to match
				    MatcherCandidate estimate = taxi.state.estimate(); // most likely position estimate

				    if(estimate == null || estimate.point() == null){
				    	continue;
				    }
				    //unconvergency
					if(converge == null){
						continue;
					}
					int id = Integer.parseInt(converge.matching_id());
					long utc = taxi.time_map.remove(id);
					//System.out.println("suid "+sample.suid+" sample_id "+taxi.sample_id+" id "+ id + " utc " + utc);
					boolean passenager = taxi.passenger_map.remove(id);
					String date = taxi.date_map.remove(id);
					Point position = converge.point().geometry(); // position
					
					Sample converge_sample = new Sample(date, sample.suid, utc, position.getY(), 
							position.getX(), 0, passenager);
					converge_sample.gid = (int)converge.point().edge().id(); // road id
				    converge_sample.offset = converge.point().fraction();
				    
				    if(converge.transition() != null ){
				    	converge_sample.route = converge.transition().route().toString(); // route to position
				    }
				    
				    //System.out.println("1 converge_sample "+converge_sample.suid+" "+ converge_sample.utc.getTime()/1000 +" "+ converge_sample.lat + " " + converge_sample.lon + " "+ converge_sample.gid);
				    
				    if(taxi.pre_converge == null){
				    	taxi.pre_converge = converge_sample;
						continue;
					}
				    
				    long interval = converge_sample.utc.getTime()/1000 - taxi.pre_converge.utc.getTime()/1000;
					//interval too long, do not process
					if(interval > 600){
						taxi.pre_converge = converge_sample;
						continue;
					}
				    
					if(taxi.pre_converge.passenger != converge_sample.passenger){
						taxi.pre_converge = converge_sample;
						continue;
					}
					//System.out.println("car "+taxi.pre_converge.suid+" "+ taxi.pre_converge.utc.getTime()/1000+ " "+ taxi.pre_converge.gid+" "+taxi.pre_converge.offset+" "+taxi.pre_converge.route+
					//		";"+converge_sample.suid+" "+ converge_sample.utc.getTime()/1000+ " "+ converge_sample.gid+" "+converge_sample.offset+" "+converge_sample.route);

				    convergeList.add(converge_sample);
				    preConvergeList.add(taxi.pre_converge);
				    
				    taxi.pre_converge = converge_sample;
				}
				//for循坏外 存储successor和neighbor
				HashMap<Road,Road> RoadSuccessor = new HashMap<Road,Road>();
				HashMap<Road,Road> RoadNeighbor = new HashMap<Road,Road>();
				
				Set<MatcherCandidate> stateVector4 = taxi.state.vector();
			    for(MatcherCandidate i4 : stateVector4)
				{
			    	RoadSuccessor.put(i4.point().edge(), i4.point().edge().successor);
			    	RoadNeighbor.put(i4.point().edge(), i4.point().edge().neighbor);
				}
			    return Optional.of(new Tuple5<>(taxi,RoadSuccessor,RoadNeighbor,preConvergeList,convergeList));			
			}
		});
		
		JavaPairDStream<ArrayList<Sample>, ArrayList<Sample>> MatcheredGPS = gpsMatcher.mapToPair(new PairFunction<Tuple2<Long, Tuple5<TaxiInfo,HashMap<Road,Road>,HashMap<Road,Road>,ArrayList<Sample>, ArrayList<Sample>>>,ArrayList<Sample>, ArrayList<Sample>>(){
			private static final long serialVersionUID = 1L;
			@Override
			public Tuple2<ArrayList<Sample>, ArrayList<Sample>> call(
					Tuple2<Long, Tuple5<TaxiInfo,HashMap<Road,Road>,HashMap<Road,Road>,ArrayList<Sample>, ArrayList<Sample>>> t)
					throws Exception {
				//preConvergeList,convergeList都>0
				//if(t._2._4().size()>0 && t._2._5().size()>0)
				//{
					//返回Long,preConvergeList,convergeList
					return new Tuple2<ArrayList<Sample>, ArrayList<Sample>>(t._2._4(), t._2._5());
				//}
				//else return null;
			}
		});
		
		//过滤后的匹配后的前后位置车辆
		/*JavaPairDStream<ArrayList<Sample>, ArrayList<Sample>> convergeSample = MatcheredGPS.filter(new Function<Tuple2<ArrayList<Sample>,ArrayList<Sample>>,Boolean>(){
			private static final long serialVersionUID = 1L;

			@Override
			public Boolean call(Tuple2<ArrayList<Sample>, ArrayList<Sample>> v1) throws Exception {
				return v1!=null;
			}
		});*/
		
		MatcheredGPS.foreachRDD(new VoidFunction<JavaPairRDD<ArrayList<Sample>,ArrayList<Sample>>>(){
			private static final long serialVersionUID = 1L;

			@Override
			public void call(JavaPairRDD<ArrayList<Sample>, ArrayList<Sample>> t) throws Exception {
				
				Line.clear();
				
				//long collectStart = System.currentTimeMillis();
				
				List<Tuple2<ArrayList<Sample>, ArrayList<Sample>>> list = t.collect();
				System.out.println("1size "+list.size() +"MatcherPartition "+t.getNumPartitions());
				
				//long collectEnd = System.currentTimeMillis();
				//System.out.println("1size "+list.size() +" collectTime "+(collectEnd-collectStart));
				
				HashMap<Integer,ArrayList<Integer>> Route = new HashMap<Integer,ArrayList<Integer>>();
				ArrayList<Integer> allRoad = new ArrayList<Integer>();
				int num = 0;
				
				for(Tuple2<ArrayList<Sample>, ArrayList<Sample>> tuple :list)
				{

					for(int i=0;i<tuple._1.size();i++)
					{
						Sample preconverge = tuple._1.get(i);
						Sample converge = tuple._2.get(i);
						String line = preconverge.date+ " " + preconverge.suid + " " + preconverge.utc.getTime()/1000 + " "
								+ preconverge.gid + " " +preconverge.offset + " " + preconverge.route
								+";"+converge.date+ " " + converge.suid + " " + converge.utc.getTime()/1000 + " "
								+ converge.gid + " " +converge.offset + " " + converge.route;
						//System.out.println(line);
						/*Line.add(line);
						
						String[] str_gids=converge.route.split(",");
						ArrayList<Integer> route_gid = new ArrayList<Integer>();
						for(int j=0; j<str_gids.length; j++){
							route_gid.add(Integer.parseInt(str_gids[j]));
							allRoad.add(Integer.parseInt(str_gids[j]));
						}
						Route.put(num,route_gid);
						num++;*/
					}
				}
				/*Set<Integer> keySet = Route.keySet();
		        for(Integer i : keySet)
		        {
		        	boolean flag = true;
		            for(Integer j :Route.get(i))//每个converge.route中的每个道路，在本batch中只能出现一次
		            {
		            	//System.out.println(j+" cishu "+Collections.frequency(allRoad, j));
		            	if(Collections.frequency(allRoad, j)!=1)
		            		flag=false;
		            }
		            if(flag==true)
		            {
		            	Line.set(i, Line.get(i)+";1");
		            }
		            else
		            	Line.set(i, Line.get(i)+";0");
		            System.out.println(Line.get(i));
		        }*/
				
				//System.out.println("Line.size()"+Line.size());
				//有消息入队后激活轮询线程
	            /*synchronized (Lock.class)
	            {
	                Lock.class.notify();
	            }*/
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
		
		/*
		MatcheredGPS.foreachRDD(new VoidFunction<JavaPairRDD<ArrayList<Sample>, ArrayList<Sample>>>(){
			private static final long serialVersionUID = 1L;
			@Override
			public void call(JavaPairRDD<ArrayList<Sample>, ArrayList<Sample>> t) throws Exception {
				//System.out.println("t.size:"+t.partitions().size());13片，和线程数量有关
				//如果不collect应该更好，collect花费的时间长
				for(Tuple2<Sample, Sample> s : t.collect())
				{
					if(s!=null && s._2!=null)
					{
						System.out.println("after:"+s._2.suid+" "+ s._2.utc.getTime()/1000 +" "+ s._2.lat + " " + s._2.lon + " "+ s._2.gid);	
					}
				}
				
				t.collect();
				SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");	
				String time = tempDate.format(new java.util.Date());
				//每秒进来的车辆数据
				System.out.println("time："+time);
			}
		});
		*/
		
		
		// 必须调用start方法,整个spark streaming应用才会启动执行,然后卡在那里,最后close释放资源
		jssc.start();		
		System.out.println("start1");
		//ServerSocket s2 = new ServerSocket(10000);
		System.out.println("start2");
		//Socket sConverge = s2.accept();//阻塞式
		System.out.println("start3");
		// 创建一个发送轨迹对的线程
		//new StreamingMainTaxiInfo().new InnerClass(sConverge.getOutputStream()).start();
		System.out.println("start4");
		jssc.awaitTermination();
		jssc.close();
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
