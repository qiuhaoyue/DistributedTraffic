package realtraffic.main;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import realtraffic.common.Common;
import realtraffic.gps.Sample;
import realtraffic.road.AllocationRoadsegment;
import scala.Tuple2;
import scala.Tuple3;

public class StreamingRoadCalculate {
	private static Connection con = null;
	private static Statement stmt = null;
	private static ResultSet rs = null;
	public static void main(String[] args) throws InterruptedException, IOException{
		PrintStream ps=new PrintStream(new FileOutputStream("/home/qhyue/RoadCalculate.txt"));
		System.setOut(ps);
		String[] date_list = {"_2010_04_07", "_2010_04_08", "_2010_04_09", "_2010_04_10", "_2010_04_11",
				"_2010_04_12", "_2010_04_13", "_2010_04_14", "_2010_04_15", "_2010_04_16", "_2010_04_17",
				 "_2010_04_18", "_2010_04_19", "_2010_04_20", "_2010_04_21", "_2010_04_22", "_2010_04_23",
				 "_2010_04_24", "_2010_04_25", "_2010_04_26", "_2010_04_27", "_2010_04_28", "_2010_04_29"};
		try{
			con = Common.getConnection();
			if (con == null) {
				return;
			}
			stmt = con.createStatement();
			
			//就只是4月14号一天的
			//跑1天的，准备取第1天的数据
			for(int i=7; i< 8; i++){		
				String sql = "";
				sql = "select min(utc),max(utc) from " + Common.ValidSampleTable + ";";
				rs = stmt.executeQuery(sql);
				if(rs.next()){
					//这两个要随着日期更新
					Common.start_utc = rs.getLong(1);
					Common.end_utc = rs.getLong(2);
				}
				//4月14号的起始时间
				//Common.start_utc = 1271174400;
				//create traffic table建当天的速度表和转向表
				Common.Date_Suffix = date_list[i];
			}
			//Thread.sleep(10*60*1000);
		}
		catch (Exception e) {
		    e.printStackTrace();
		}
		finally{
			try {
				con.commit();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		Common.init(40000);//initialize map, matchers
		Common.init_roadlist();//initialize roadmap and initial state
		final AllocationRoadsegment[] roadlist = new AllocationRoadsegment[(int)200581+1];
		for(int i=0;i<roadlist.length;i++){
		    roadlist[i]=Common.roadlist[i];
		}

		SparkConf conf = new SparkConf().setAppName("RoadCalculate").setMaster("local[4]");
		// 该对象除了接受SparkConf对象,还要接受一个Batch Interval参数,就是说,每收集多长时间数据划分一个batch去进行处理
		// Durations里面可以设置分钟、毫秒、秒,这里设置1秒
		final JavaStreamingContext jssc = new JavaStreamingContext(conf,Durations.seconds(10));
		
		final Broadcast<double[][]>  default_traffic = jssc.sparkContext().broadcast(Common.default_traffic);
		final Broadcast<double[][]>  default_class_traffic = jssc.sparkContext().broadcast(Common.default_class_traffic);
		// 首先创建输入DStream,代表一个数据源比如从socket或kafka来持续不断的进入实时数据流
		// 创建一个监听端口的socket数据流,这里面就会有每隔一秒生成一个RDD,RDD的元素类型为String就是一行一行的文本
		JavaReceiverInputDStream<String> lines = jssc.socketTextStream("localhost", 10000);
		/*lines.foreachRDD(new VoidFunction<JavaRDD<String>>(){

			private static final long serialVersionUID = 1L;

			@Override
			public void call(JavaRDD<String> t) throws Exception {
				List<String> a = t.collect();
				System.out.println("===="+a.size());
				for(String b :a)
				{
					System.out.println(b);
				}
				
			}
			
		});*/
		//jssc.checkpoint("/mnt/freenas/cheakpoint");
		JavaPairDStream<Sample, Sample> converge = lines.mapToPair(new PairFunction<String,Sample,Sample>(){
			private static final long serialVersionUID = 1L;
			@Override
			public Tuple2<Sample, Sample> call(String v1) throws Exception {
				String pre = v1.split(";")[0];
				String cur = v1.split(";")[1];
				int distributed = Integer.parseInt(v1.split(";")[2]);
				
				String preDate = pre.split(" ")[0];
				long preSuid = Long.parseLong(pre.split(" ")[1]);
				long preUtc = Long.parseLong(pre.split(" ")[2]);
				int preGid = Integer.parseInt(pre.split(" ")[3]);
				double preOffset = Double.parseDouble(pre.split(" ")[4]);
				String preRoute = pre.split(" ")[5];
				
				String curDate = cur.split(" ")[0];
				long curSuid = Long.parseLong(cur.split(" ")[1]);
				long curUtc = Long.parseLong(cur.split(" ")[2]);
				int curGid = Integer.parseInt(cur.split(" ")[3]);
				double curOffset = Double.parseDouble(cur.split(" ")[4]);
				String curRoute = cur.split(" ")[5];
				
				Sample preconverge = new Sample(preDate, preSuid, preUtc, preGid, preOffset, preRoute, distributed);
				Sample converge = new Sample(curDate, curSuid, curUtc, curGid, curOffset, curRoute, distributed);  
				return new Tuple2<Sample,Sample>(preconverge,converge);
			}
		});
		//可以分布式更新路况的车辆
		JavaPairDStream<Sample, Sample> distributedCar = converge.filter(new Function<Tuple2<Sample,Sample>,Boolean>(){
			private static final long serialVersionUID = 1L;

			@Override
			public Boolean call(Tuple2<Sample, Sample> v1) throws Exception {
				// TODO Auto-generated method stub
				return v1._2.distributed==1;
			}
		});
		
		//在一个batch中出现道路次数大于1的车辆，只能按时间更新
		JavaPairDStream<Sample, Sample> OrderCar = converge.filter(new Function<Tuple2<Sample,Sample>,Boolean>(){
			private static final long serialVersionUID = 1L;

			@Override
			public Boolean call(Tuple2<Sample, Sample> v1) throws Exception {
				// TODO Auto-generated method stub
				return v1._2.distributed==0;
			}
		});
		//有先后顺序的只能按时间先后更新
		
		OrderCar.foreachRDD(new VoidFunction<JavaPairRDD<Sample,Sample>>(){
			private static final long serialVersionUID = 1L;

			@Override
			public void call(JavaPairRDD<Sample, Sample> t) throws Exception {
				List<Tuple2<Sample, Sample>> carList = new ArrayList<Tuple2<Sample, Sample>>(t.collect());
				//System.out.println("===");
				//按时间排序
				Collections.sort(carList,new Comparator<Tuple2<Sample, Sample>>(){
					@Override
					public int compare(Tuple2<Sample, Sample> o1, Tuple2<Sample, Sample> o2) {
						if(o1._2.utc.getTime()/1000==o2._2.utc.getTime()/1000)
						{
							return 0;
						}
						else if(o1._2.utc.getTime()/1000>o2._2.utc.getTime()/1000)
						{
							return 1;
						}
						else 
							return -1;
					}
		        });
				
				//连接数据库
				/*Connection con = null;
				Statement stmt = null;
				try{
					con = Common.getConnection();
					stmt = con.createStatement();
					con.setAutoCommit(false);
				}
				catch (SQLException e) {
				    e.printStackTrace();
				}*/
				
				for(Tuple2<Sample, Sample> car : carList)
				{
					Sample pre_converge = car._1;
					Sample converge_sample = car._2;
					if(pre_converge.gid != converge_sample.gid){
						//calculate turning time
						ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> updateList = estimite_turning(pre_converge, converge_sample,default_traffic,default_class_traffic);
						for(Tuple2<AllocationRoadsegment, ArrayList<String>> road :updateList)
						{
							//更新路况
							
							System.out.println("pre "+roadlist[(int) road._1.gid].gid+" speed "+roadlist[(int) road._1.gid].avg_speed);
							
							roadlist[(int) road._1.gid] = road._1;
							
							System.out.println("after "+roadlist[(int) road._1.gid].gid+" speed "+roadlist[(int) road._1.gid].avg_speed);
							Set<Integer> keySet = roadlist[(int) road._1.gid].get_all_turning_time().keySet();
					        for(Integer road_gid : keySet)
					        {
					            System.out.println("turningtime "+road._1.gid+" "+road_gid+" "+roadlist[(int) road._1.gid].get_all_turning_time().get(road_gid));
					        }
							
							//更新到数据库
							/*for(String sql : road._2)
							{
								stmt.addBatch(sql);
							}*/
						}
					}
					else{
						Tuple2<AllocationRoadsegment, ArrayList<String>> update = estimite_road(pre_converge, converge_sample,default_traffic,default_class_traffic);
						//更新路况
						
						System.out.println("pre "+roadlist[(int) update._1.gid].gid+" speed "+roadlist[(int) update._1.gid].avg_speed);
						
						roadlist[(int) update._1.gid] = update._1;
						
						System.out.println("after "+roadlist[(int) update._1.gid].gid+" speed "+roadlist[(int) update._1.gid].avg_speed);
						Set<Integer> keySet = roadlist[(int) update._1.gid].get_all_turning_time().keySet();
				        for(Integer road_gid : keySet)
				        {
				            System.out.println("turningtime "+update._1.gid+" "+road_gid+" "+roadlist[(int) update._1.gid].get_all_turning_time().get(road_gid));
				        }

						//更新到数据库
						/*for(String sql : update._2)
						{
							stmt.addBatch(sql);
						}
						*/
						
					}
					
				}
				
				/*try{
					stmt.executeBatch();
					stmt.clearBatch();
				}
				catch (SQLException e) {
				    e.printStackTrace();
				}
				try{
					if(stmt!=null)
					{
						stmt.close();
					}
					
	                if(con!=null)
	                {
	                	con.commit();
	                    con.close();
	                }	                	
	            }
	            catch(SQLException e){
	            	e.printStackTrace();
	            }
				*/
				
			}

			private Tuple2<AllocationRoadsegment, ArrayList<String>> estimite_road(Sample pre_converge,
					Sample converge_sample, Broadcast<double[][]> default_traffic,
					Broadcast<double[][]> default_class_traffic) {
				double offset = Math.abs(converge_sample.offset - pre_converge.offset);
				long interval = converge_sample.utc.getTime()/1000 - pre_converge.utc.getTime()/1000;
				int gid = converge_sample.gid;
				AllocationRoadsegment road = roadlist[gid];
				
				if(interval == 0){
					return null;
				}
				//slow down
				if(offset == 0){
					//consider it traffic jam
					if(road.avg_speed < 2){
						return road.update_speed_sample(0, converge_sample,default_traffic,default_class_traffic);
					}
					//consider it error
					return null;
				}
				
				//update speed
				double speed = offset * road.length / interval;
				return road.update_speed_sample(speed, converge_sample,default_traffic,default_class_traffic);
			}

			private ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> estimite_turning(Sample pre_converge,
					Sample converge_sample, Broadcast<double[][]> default_traffic,
					Broadcast<double[][]> default_class_traffic) {
				if(converge_sample.route == null){
					return null;
				}
				long interval = converge_sample.utc.getTime()/1000 - pre_converge.utc.getTime()/1000;
				double  total_length = 0;
				
				ArrayList<Double> coverage_list = new ArrayList<Double>();
				//construct route gid list and coverage list
				String[] str_gids=converge_sample.route.split(",");
				
				ArrayList<Integer> route_gid = new ArrayList<Integer>();
				//first road
				route_gid.add(Integer.parseInt(str_gids[0]));
				
				//previous match is right
				if(pre_converge.gid == Integer.parseInt(str_gids[0])){
					coverage_list.add(1 - pre_converge.offset);
					total_length += roadlist[pre_converge.gid].length * (1 - pre_converge.offset);
				}
				//match wrong
				else{
					//just a estimated value
					coverage_list.add(0.5);
					total_length += roadlist[Integer.parseInt(str_gids[0])].length * (1 - pre_converge.offset);
				}
				//fully covered road
				for(int i=1; i<str_gids.length-1; i++){
					route_gid.add(Integer.parseInt(str_gids[i]));
					coverage_list.add(1.0);
					total_length += roadlist[Integer.parseInt(str_gids[i])].length;
				}
				//last road
				route_gid.add(Integer.parseInt(str_gids[str_gids.length-1]));
				coverage_list.add(converge_sample.offset);
				total_length += roadlist[Integer.parseInt(str_gids[str_gids.length-1])].length * converge_sample.offset;
				if(total_length / interval > 33.33){
					//Common.logger.debug("route wrong, too fast");
					return null;
				}
				//start calulate route time
				//calculate total time
				double total_time = 0;
				for(int i=0; i<route_gid.size(); i++){
					int gid = route_gid.get(i);
					double coverage = coverage_list.get(i);
					total_time += coverage * roadlist[gid].time;
					//add turning time
					if(i != route_gid.size()-1){
						total_time += roadlist[gid].get_turning_time(route_gid.get(i+1));
					}
				}
				if(total_time == 0){
					return null;
				}
				//返回更新的路况和更新数据库表
				ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> update = new ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>>();
				
				//calculate time in each road	
				for(int i=0; i<route_gid.size(); i++){
					int gid = route_gid.get(i);
					double coverage = coverage_list.get(i);
					double new_road_time;
					double new_turning_time = -1;
					double road_time = roadlist[gid].time;
					double turning_time = 4;
					
					double percentage;//percentage of travel time in total time, not real
					double travel_time;//real travel time
					if(i != route_gid.size()-1){
						//avoid some bug of map matching
						if(gid == route_gid.get(i+1)){
							continue;
						}
						turning_time = roadlist[gid].get_turning_time(route_gid.get(i+1));
						road_time = roadlist[gid].time * coverage;
						percentage = (road_time + turning_time)/total_time;
						//percentage = road_time/total_time;
						travel_time = interval * percentage;
						new_road_time = travel_time * road_time /(road_time + turning_time);
						//new_road_time = travel_time;
						if(coverage != 0){
							new_road_time /= coverage;
						}
						new_turning_time = travel_time * turning_time /(road_time + turning_time);
					}
					else{
						percentage = (coverage * roadlist[gid].time)/total_time;
						travel_time = interval * percentage;
						new_road_time = travel_time; //no turning time
						if(coverage != 0){
							new_road_time /= coverage;
						}
					}
					//update road time		
					//Integer是否是-3？
					Tuple3<Integer, AllocationRoadsegment, ArrayList<String>> update_time = roadlist[gid].update_time(new_road_time, converge_sample, default_class_traffic, default_class_traffic);
					
					int cur_seq = update_time._1();
					if(cur_seq == -3){
						update.add(new Tuple2<AllocationRoadsegment, ArrayList<String>>(update_time._2(),update_time._3()));
						continue;
					}
					
					//update turning time
					if(new_turning_time > 0){
						update_time._2().update_turning_time(route_gid.get(i+1), 
								new_turning_time, cur_seq);
					}
					update.add(new Tuple2<AllocationRoadsegment, ArrayList<String>>(update_time._2(),update_time._3()));
				}
				return update;
				
			}
		});
		JavaDStream<ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>>> roadSpeed = distributedCar.map(new Function<Tuple2<Sample,Sample>,ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>>>(){

			private static final long serialVersionUID = 1L;
			@Override
			public ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> call(Tuple2<Sample, Sample> t) throws Exception {
				
				Sample pre_converge = t._1;
				Sample converge_sample = t._2;
				if(pre_converge.gid != converge_sample.gid){
					//calculate turning time
					ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> updateList = estimite_turning(pre_converge, converge_sample,default_traffic,default_class_traffic);
				
					return updateList;
				}
				//just estimite traffic by single point	
				else{
					//前后两点在同一条道路上，估计道路速度
					Tuple2<AllocationRoadsegment, ArrayList<String>> update = estimite_road(pre_converge, converge_sample,default_traffic,default_class_traffic);
					if(update!=null)
					{
						Tuple2<AllocationRoadsegment, ArrayList<String>> up = new Tuple2<AllocationRoadsegment, ArrayList<String>>(update._1,update._2);
						ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> updateList = new ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>>();
						updateList.add(up);
						return updateList;
					}
					else return null;
				}

			}
			private ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> estimite_turning(Sample pre_converge, Sample converge_sample,Broadcast<double[][]> default_traffic,Broadcast<double[][]> default_class_traffic){
				if(converge_sample.route == null){
					return null;
				}
				long interval = converge_sample.utc.getTime()/1000 - pre_converge.utc.getTime()/1000;
				double  total_length = 0;
				
				ArrayList<Double> coverage_list = new ArrayList<Double>();
				//construct route gid list and coverage list
				String[] str_gids=converge_sample.route.split(",");
				
				ArrayList<Integer> route_gid = new ArrayList<Integer>();
				//first road
				route_gid.add(Integer.parseInt(str_gids[0]));
				
				//previous match is right
				if(pre_converge.gid == Integer.parseInt(str_gids[0])){
					coverage_list.add(1 - pre_converge.offset);
					total_length += roadlist[pre_converge.gid].length * (1 - pre_converge.offset);
				}
				//match wrong
				else{
					//just a estimated value
					coverage_list.add(0.5);
					total_length += roadlist[Integer.parseInt(str_gids[0])].length * (1 - pre_converge.offset);
				}
				//fully covered road
				for(int i=1; i<str_gids.length-1; i++){
					route_gid.add(Integer.parseInt(str_gids[i]));
					coverage_list.add(1.0);
					total_length += roadlist[Integer.parseInt(str_gids[i])].length;
				}
				//last road
				route_gid.add(Integer.parseInt(str_gids[str_gids.length-1]));
				coverage_list.add(converge_sample.offset);
				total_length += roadlist[Integer.parseInt(str_gids[str_gids.length-1])].length * converge_sample.offset;
				if(total_length / interval > 33.33){
					//Common.logger.debug("route wrong, too fast");
					return null;
				}
				//start calulate route time
				//calculate total time
				double total_time = 0;
				for(int i=0; i<route_gid.size(); i++){
					int gid = route_gid.get(i);
					double coverage = coverage_list.get(i);
					total_time += coverage * roadlist[gid].time;
					//add turning time
					if(i != route_gid.size()-1){
						total_time += roadlist[gid].get_turning_time(route_gid.get(i+1));
					}
				}
				if(total_time == 0){
					return null;
				}
				//返回更新的路况和更新数据库表
				ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> update = new ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>>();
				
				//calculate time in each road	
				for(int i=0; i<route_gid.size(); i++){
					int gid = route_gid.get(i);
					double coverage = coverage_list.get(i);
					double new_road_time;
					double new_turning_time = -1;
					double road_time = roadlist[gid].time;
					double turning_time = 4;
					
					double percentage;//percentage of travel time in total time, not real
					double travel_time;//real travel time
					if(i != route_gid.size()-1){
						//avoid some bug of map matching
						if(gid == route_gid.get(i+1)){
							continue;
						}
						turning_time = roadlist[gid].get_turning_time(route_gid.get(i+1));
						road_time = roadlist[gid].time * coverage;
						percentage = (road_time + turning_time)/total_time;
						//percentage = road_time/total_time;
						travel_time = interval * percentage;
						new_road_time = travel_time * road_time /(road_time + turning_time);
						//new_road_time = travel_time;
						if(coverage != 0){
							new_road_time /= coverage;
						}
						new_turning_time = travel_time * turning_time /(road_time + turning_time);
					}
					else{
						percentage = (coverage * roadlist[gid].time)/total_time;
						travel_time = interval * percentage;
						new_road_time = travel_time; //no turning time
						if(coverage != 0){
							new_road_time /= coverage;
						}
					}
					//update road time		
					//Integer是否是-3？
					Tuple3<Integer, AllocationRoadsegment, ArrayList<String>> update_time = roadlist[gid].update_time(new_road_time, converge_sample, default_class_traffic, default_class_traffic);
					
					int cur_seq = update_time._1();
					if(cur_seq == -3){
						update.add(new Tuple2<AllocationRoadsegment, ArrayList<String>>(update_time._2(),update_time._3()));
						continue;
					}
					
					//update turning time
					if(new_turning_time > 0){
						update_time._2().update_turning_time(route_gid.get(i+1), 
								new_turning_time, cur_seq);
					}
					update.add(new Tuple2<AllocationRoadsegment, ArrayList<String>>(update_time._2(),update_time._3()));
				}
				return update;
				
			}
			
			private Tuple2<AllocationRoadsegment, ArrayList<String>>  estimite_road(Sample pre_converge, Sample converge_sample,Broadcast<double[][]> default_traffic,Broadcast<double[][]> default_class_traffic){
				double offset = Math.abs(converge_sample.offset - pre_converge.offset);
				long interval = converge_sample.utc.getTime()/1000 - pre_converge.utc.getTime()/1000;
				int gid = converge_sample.gid;
				AllocationRoadsegment road = roadlist[gid];
				
				if(interval == 0){
					return null;
				}
				//slow down
				if(offset == 0){
					//consider it traffic jam
					if(road.avg_speed < 2){
						return road.update_speed_sample(0, converge_sample,default_traffic,default_class_traffic);
						
					}
					//consider it error
					return null;
				}
				
				//update speed
				double speed = offset * road.length / interval;
				return road.update_speed_sample(speed, converge_sample,default_traffic,default_class_traffic);
			}
			
		});

		
		roadSpeed.foreachRDD(new VoidFunction<JavaRDD<ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>>>>(){

			private static final long serialVersionUID = 1L;
			@Override
			public void call(JavaRDD<ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>>> t) throws Exception {
				//只是把这个batch的路况和该更新的数据库sql收集起来，还没更新到数据库
				/*Connection con = null;
				Statement stmt = null;
				try{
					con = Common.getConnection();
					stmt = con.createStatement();
					con.setAutoCommit(false);
				}
				catch (SQLException e) {
				    e.printStackTrace();
				}*/
				
				List<ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>>>  allList = t.collect();
				if(allList!=null)
				{
					for(ArrayList<Tuple2<AllocationRoadsegment, ArrayList<String>>> list:allList)
					{
						if(list!=null)
						{
							for(Tuple2<AllocationRoadsegment, ArrayList<String>> road :list)
							{
								if(road!=null)
								{
									//更新路况，道路不会重复
									System.out.println("pre "+roadlist[(int) road._1.gid].gid+" speed "+roadlist[(int) road._1.gid].avg_speed);
									roadlist[(int) road._1.gid] = road._1;
									System.out.println("after "+roadlist[(int) road._1.gid].gid+" speed "+roadlist[(int) road._1.gid].avg_speed);
									Set<Integer> keySet = roadlist[(int) road._1.gid].get_all_turning_time().keySet();
							        for(Integer road_gid : keySet)
							        {
							            System.out.println("turningtime "+road._1.gid+" "+road_gid+" "+roadlist[(int) road._1.gid].get_all_turning_time().get(road_gid));
							        }
									
									//更新到数据库
									/*for( String sql : road._2)
									{
										stmt.addBatch(sql);
									}*/
								}
								
							}
						}
						
					}
				}
				
				/*try{
					stmt.executeBatch();
					stmt.clearBatch();
				}
				catch (SQLException e) {
				    e.printStackTrace();
				}
				try{
					if(stmt!=null)
					{
						stmt.close();
					}
					
	                if(con!=null)
	                {
	                	con.commit();
	                    con.close();
	                }	                	
	            }
	            catch(SQLException e){
	            	e.printStackTrace();
	            }*/
			}
			
		});
		
		
		
		/*distributedCar.foreachRDD(new VoidFunction<JavaPairRDD<Sample,Sample>>(){
			private static final long serialVersionUID = 1L;

			@Override
			public void call(JavaPairRDD<Sample, Sample> t) throws Exception {
				List<Tuple2<Sample, Sample>> list = t.collect();
				System.out.println("===");
				
				for(Tuple2<Sample, Sample> tuple :list)
				{
					Sample converge = tuple._2;
					System.out.println("converge_sample "+converge.suid+" "+ converge.utc.getTime()/1000 +" "+ converge.gid+" "+converge.offset + " "+converge.route);
				}
			}
			
		});*/
		
		
		jssc.start();
		jssc.awaitTermination();
		jssc.close();
	}
}
