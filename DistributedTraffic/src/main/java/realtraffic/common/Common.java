package realtraffic.common;

import realtraffic.gps.Sample;
import realtraffic.process.ConvergeCar;
import realtraffic.process.ProcessThread;
import realtraffic.process.TaxiInfo;
import realtraffic.road.AllocationRoadsegment;
import realtraffic.road.RoadConstant;
import realtraffic.updater.HistoryTrafficUpdater;
import realtraffic.updater.RealTrafficUpdater;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import com.bmwcarit.barefoot.matcher.Matcher;
import com.bmwcarit.barefoot.road.PostGISReader;
import com.bmwcarit.barefoot.roadmap.Road;
import com.bmwcarit.barefoot.roadmap.RoadMap;
import com.bmwcarit.barefoot.roadmap.RoadPoint;
import com.bmwcarit.barefoot.roadmap.TimePriority;
import com.bmwcarit.barefoot.spatial.Geography;
import com.bmwcarit.barefoot.topology.Dijkstra;
import com.bmwcarit.barefoot.util.Tuple;

import realtraffic.common.Common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.postgresql.util.PSQLException;


public class Common {
	
	//database config
	public static String JdbcUrl  = "jdbc:postgresql://localhost:5432/";
	public static String Host     = "localhost";
	public static int Port        = 5432;
	
	public static String UserName = "postgres";
	public static String UserPwd  = "qhy85246";
	public static String DataBase = "distributedrouting";
	
	public static String OriginWayTable = "ways";
	public static String ValidSampleTable  = "valid_gps_utc";
	public static String overpassRoad = "overpassRoad";
	static String FilterSampleTable = ValidSampleTable;
	
	//traffic table
	public static String real_road_slice_table = "real_road_time_slice_";
	public static String real_turning_slice_table ="real_turning_time_slice_";
	public static String history_road_slice_table = "history_road_time_slice_";
	public static String overpass_turning_slice_table ="overpass_turning_time_slice_";

	public static String extract_ways = "extract_ways";
	//restore table
	public static String restore_sample_table = "restore_sample";//backup of unprocessed samples
	public static String restore_road_table= "restore_road_traffic";//backup of road traffic
	public static String restore_turning_table= "restore_turning_traffic";//backup of turning traffic
	
	static String UnKnownSampleTable = "match_fail_gps";
	
	//to create table with date
	public static String Date_Suffix = "";
	
	public static double max_speed = 33.33;
	public static double min_speed = 1.5;
	static double min_interval = 10;
	static double speed_alpha = 0.9;
	public static double history_update_alpha = 0.8;// to update history traffic
	public static double init_turning_time = 4;
	public static int delay_update_thresold = 1;//to get delay updated traffic
	public static int max_infer_traffic_interval = 2;// to infer the speed if no traffic sensed in the interval
	public static double smooth_delta = 0.1;// to smooth traffic
	
	public static double infer_alpha = 0.8;
	
	public static int MIN_GPS_INTERVAL = 10;
	public static int MAX_GPS_INTERVAL = 600;//by second
	
	public static double MIN_TURNING_TIME = 0.1;// avoid too small turning time
	
	public static int match_windows_size = 6;
	
	public static long period = 300L;
	
	public static long start_utc;
	public static long end_utc;
	
	public static int max_seg = 288;// 5 minutes a period
	
	//to control speed of data emission
	public static int emission_step = 1;//send points within next x seconds every time
	public static int emission_multiple = 1;//times of speed of real time.
	
	//for experiment
	public static double dump_percentage = 0.1; //dump points in high-density area randomly for approximation
	public static int max_capacity = 220;
	
	public static double area_min_lon = 116.244237125;
	public static double area_max_lon = 116.49714415;
	public static double area_min_lat = 39.804331375;
	public static double area_max_lat = 40.06560055;
	
	public static int dump_number = 0;
	public static int dump_number_0414 = 0;
	//taxi info
	public static TaxiInfo taxi[] = null;//taxi sample
	
	//roadmap
	public static AllocationRoadsegment[] roadlist=null;
	public static HashMap<Integer, RoadConstant> roadConstant = null;
	//public static ArrayList<Integer> roadArrayList = new ArrayList<Integer>();
	
	//default speed
	public static  double[][] default_traffic = null;
	
	//average speed of roads in same class
	public static  double[][] default_class_traffic = null;
	
	public static Logger logger = LogManager.getLogger(Common.class.getName());
	
	public static RoadMap map = null;
	public static Matcher matcher;
	
	//static GPSUpdater gps_updater;
	//public static GPSUpdater unkown_gps_updater;//points that match failed
	public static RealTrafficUpdater real_traffic_updater;
	public static HistoryTrafficUpdater history_traffic_updater;
	
	//config Mapping of road class identifiers to priority factor and default maximum speed
	public static Map<Short, Tuple<Double, Integer>> road_config;

	/**存储所有立交区域道路*/
	public static ArrayList<Integer> Overpass_Road =null;
	/**存储道路连接点只连接两条道路的道路gid*/
	public static ArrayList<Integer> extract_ways_gid2 = null;
	public static ArrayList<Integer> extract_ways_gid1 = null;

	//for debug calculate time
	public static int thread_number;
	public static ProcessThread[] thread_pool;
	
	//change rate of traffic
	public static ArrayList<Long>[] cost_all;
	public static ArrayList<Long>[] cost_matching;
	
	public static int[][][] geohash_counter;
	public static int[][] road_counter;
	public static int split_size = 100;
	public static double max_lat = 40.23978;
	public static double min_lat = 39.5430622;
	public static double max_lon = 117.0029582;
	public static double min_lon = 115.9913301;
	public static double lat_interval;
	public static double lon_interval;
	
	
	//initialize params
	public static void init(int max_suid){	
		try{
			//init road config
			init_road_config();
			map = RoadMap.Load(new PostGISReader(Common.Host, Common.Port, 
					DataBase, OriginWayTable, UserName, UserPwd, road_config));
			map.construct();
			matcher = new Matcher(map, new Dijkstra<Road, RoadPoint>(),
		            new TimePriority(), new Geography());
			//history_traffic_updater = new HistoryTrafficUpdater();
			//real_traffic_updater = new RealTrafficUpdater();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		//lat_interval = (max_lat - min_lat) / split_size;
		//lon_interval = (max_lon - min_lon) / split_size;

	}
	
	//create traffic table named with date
	public static void init_traffic_table() throws SQLException{
		real_traffic_updater.create_traffic_table(Date_Suffix);
		
	}
	
	
	public static void init_road_config(){
		road_config = new HashMap<Short, Tuple<Double, Integer>>();
		short class_id[] = {100,101,102,104,105,106,107,108,109,110,111,112,113,114,
				117,118,119,120,122,123,124,125,201,202,301,303,304,305};
		double priority[] = {1.30,1.0,1.10,1.04,1.12,1.08,1.15,1.10,1.20,1.12,1.25,1.30,
				1.50,1.75,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30,1.30};
		for(int i=0; i<28; i++){
			road_config.put(class_id[i], new Tuple<Double, Integer>(priority[i], (int)Common.max_speed));
		}
	}
	
	public static void dropConnection(Connection con){
		try{
			if(con!=null){
				con.close();
			}
		}
		catch (Exception e) {
		    e.printStackTrace();
		}
	}
	
	public static Connection getConnection(){
		Connection con=null;
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
			return null;
		}
		try {
			con = DriverManager.getConnection(JdbcUrl + DataBase, UserName, UserPwd);
			con.setAutoCommit(false);
			
		} catch (SQLException e) {
			e.printStackTrace();
			return null;
		}	
		return con;
	}
	public static Connection getConnection(String DataBase){
		Connection con=null;
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException e) {
			Common.logger.error("Where is your PostgreSQL JDBC Driver? " + "Include in your library path!");
			e.printStackTrace();
			return null;
		}
		try {
			con = DriverManager.getConnection(JdbcUrl + DataBase, UserName, UserPwd);
			con.setAutoCommit(false);
			
		} catch (SQLException e) {
			Common.logger.error("Connection Failed! Check output console");
			e.printStackTrace();
			return null;
		}
		return con;
	}
	
	public static void init_roadlist(){
		
		//get max gid
		Iterator<Road> roadmap = map.edges();
		long max_gid = -1;//max_gid is not equal to size
		while(roadmap.hasNext()){
			Road road = roadmap.next();
			long gid = road.id();
			if(gid > max_gid){
				max_gid = gid;
			}
		}
		//System.out.println("max_gid"+max_gid);
		roadlist=new AllocationRoadsegment[(int)max_gid+1];
		//固定的道路信息
		//roadConstant = new HashMap<Integer,RoadConstant>();
		for(int i=0;i<roadlist.length;i++){
		    roadlist[i]=new AllocationRoadsegment();
		}
		//read history traffic to memory as initial valueֵ
		try {
			init_default_traffic();
			
		} catch (SQLException e) {
			e.printStackTrace();
		}
		
		roadmap = map.edges();
		//read roadmap
		while(roadmap.hasNext()){
			Road road = roadmap.next();
			int gid = (int)road.id();
			double maxspeed = road.maxspeed();
			AllocationRoadsegment cur_road=new AllocationRoadsegment(gid,maxspeed, 10.0);	
			cur_road.length = road.length();//meters
		
			//set initial speed
			double default_speed = default_traffic[gid][288];
			//no direct default speed, use average speed of roads in same class_id
			if(default_speed <=0){
				default_speed = default_class_traffic[road.type()][288];
			}
			//if no default speed of roads in same class_id, set 10
			cur_road.avg_speed = default_speed > 0 ? default_speed : 10;
			
			cur_road.time = cur_road.length / cur_road.avg_speed;
			cur_road.base_gid = road.base().id();
			cur_road.class_id = road.type();
			roadlist[(int)gid] = cur_road;
			//roadConstant.put(gid, new RoadConstant(road.base().id(), road.length(), road.type(),cur_road.avg_speed));
			//roadArrayList.add(gid);
		}
		
	}
	
	public static void init_overpassRoad() throws SQLException
	{
		Overpass_Road = new ArrayList<Integer> ();
		Connection con = getConnection("routing");
		Statement stmt = con.createStatement();
		ResultSet rs = null;
		String sql = "";
		try {
			sql = "select gid from " + Common.overpassRoad + ";";
			rs = stmt.executeQuery(sql);
			while(rs.next())
			{
				Overpass_Road.add(rs.getInt("gid"));
			}
		} catch (PSQLException e) {
			e.printStackTrace();
		}
		catch (SQLException e) {
		    e.printStackTrace();
		}
		finally{
			con.commit();
			try {
	            if (rs!=null) {
	                rs.close();
	            }
	        } catch (Exception e) {
	            
	            e.printStackTrace();
	        }
			try {
	            if (stmt!=null) {
	                stmt.close();
	            }
	        } catch (Exception e) {
	            
	            e.printStackTrace();
	        }
			try {
	            
	            if (con!=null) {
	                con.close();
	            }
	            
	        } catch (Exception e) {
	            
	            e.printStackTrace();
	        }
		}
		
	}
	public static void init_extract_ways() throws SQLException
	{
		extract_ways_gid1= new ArrayList<Integer>();
		extract_ways_gid2= new ArrayList<Integer>();
		Connection con = getConnection("routing");
		Statement stmt = con.createStatement();
		ResultSet rs = null;
		String sql = "";
		try {
			sql = "select * from " + extract_ways + ";";
			rs = stmt.executeQuery(sql);
			while(rs.next())
			{
				int gid1 = rs.getInt("gid1");
				int gid2 = rs.getInt("gid2");
				extract_ways_gid1.add(gid1);
				extract_ways_gid2.add(gid2);
			}
		}catch (SQLException e) {
		    e.printStackTrace();
		}
		finally{
			con.commit();
			try {
	            if (rs!=null) {
	                rs.close();
	            }
	        } catch (Exception e) {
	            
	            e.printStackTrace();
	        }
			try {
	            if (stmt!=null) {
	                stmt.close();
	            }
	        } catch (Exception e) {
	            
	            e.printStackTrace();
	        }
			try {
	            
	            if (con!=null) {
	                con.close();
	            }
	            
	        } catch (Exception e) {
	            
	            e.printStackTrace();
	        }
		}
	}
	//get history traffic as default value
	public static void init_default_traffic() throws SQLException{
		int length = Common.roadlist.length;
		default_traffic = new double[length][(int)Common.max_seg + 1];
		//28 classes of road ,max id is 305, set 350 here
		default_class_traffic = new double [350][(int)Common.max_seg + 1];
		//start read traffic from database
		Connection con = Common.getConnection();
		try{
			//read default road speed
			Statement stmt = con.createStatement();
			//read by period
			for(int i=1; i<=Common.max_seg; i++){
				String traffic_table = Common.history_road_slice_table + i;
				//read data
				String sql = "select * from " + traffic_table + ";";
				ResultSet rs = stmt.executeQuery(sql);
				int[] class_id_counter = new int[350];
				while(rs.next()){
					int gid = rs.getInt("gid");
					int class_id = rs.getInt("class_id");
					class_id_counter[class_id]++;
					//Common.logger.debug(gid);
					double speed = rs.getDouble("average_speed");
					default_traffic[gid][i] = speed;
					default_class_traffic[class_id][i] += speed;
				}
				//get average speed of roads in same class
				for(int j=0; j<class_id_counter.length; j++){
					int counter = class_id_counter[j];
					if(counter > 0){
						default_class_traffic[j][i] /= counter;
					}
				}
			}
		}
		catch (SQLException e) {
			e.printStackTrace();
			con.rollback();
		}
		finally{
			con.commit();
		}		
	}
	
	//return slice number of sample by utc
	public static int get_seq(Sample sample){
		int seq_num=(int)(288-(end_utc-sample.utc.getTime()/1000)/period);
		//judge points at 00:00
		if(seq_num == 0 && sample.utc.getTime()/1000 == start_utc){
			seq_num += 1;
		}
		//points from the day before
		while(seq_num <= 0){
			seq_num += 288;
		}
		//sometimes there are some points from next day
		if(seq_num > 288){
			seq_num = 288;
		}
		return seq_num;
	}
	//return slice number of sample by utc
	public static int get_seq(ConvergeCar convergeCar){
		int seq_num=(int)(288-(end_utc-convergeCar.utc)/period);
		//judge points at 00:00
		if(seq_num == 0 && convergeCar.utc == start_utc){
			seq_num += 1;
		}
		//points from the day before
		while(seq_num <= 0){
			seq_num += 288;
		}
		//sometimes there are some points from next day
		if(seq_num > 288){
			seq_num = 288;
		}
		return seq_num;
	}
	
	public static void add_cost(boolean is_total, int seq, long cost){
		//points waiting for data from the next day to be imported
		if(cost > 25 * 60 * 1000){//points cross two days, dump it
			//cost -= 25 * 60 * 1000;
			return;
		}
		if(is_total){
			synchronized(cost_all[seq]){
				cost_all[seq].add(cost);
			}
		}
		else{
			synchronized(cost_matching[seq]){
				cost_matching[seq].add(cost);
			}
		}	
	}
	
	public static void get_ave_cost(boolean is_total, String path){
		long[][] average_cost_all = new long[1][Common.max_seg + 1];
		for(int i=1; i<=Common.max_seg; i++){
			int counter = 0;
			long counter_cost = 0;
			if(is_total){
				for(long cost:Common.cost_all[i]){
					if(cost > 0){
						counter_cost += cost;
						counter++;
					}
				}
			}
			else{
				for(long cost:Common.cost_matching[i]){
					if(cost > 0){
						counter_cost += cost;
						counter++;
					}
				}
			}
			
			average_cost_all[0][i] = counter_cost / counter;
			Common.logger.debug("average cost: " + i + ":" + average_cost_all[0][i]);
		}
		//Chart.output(average_cost_all, path);
	}
	public static void print_density(){
		for(int i=1; i<=Common.max_seg; i++){
			Common.logger.debug("seq: " + i);
			for(int j=0; j<split_size; j++){
				for(int k=0; k<split_size; k++){
					if(Common.geohash_counter[i][j][k] >= 300){
						Common.logger.debug(j + ", " + k + ": " + Common.geohash_counter[i][j][k]);
					}
				}
			}
			/*HashMap<String, Integer> map = Common.geohash_counter[i];
			Iterator iter = map.entrySet().iterator();
			while(iter.hasNext()){
				Map.Entry entry = (Map.Entry)iter.next();
				String key = (String) entry.getKey();
				int val = (int) entry.getValue();
				if(val >=50){
					Common.logger.debug(key + ":" + val);
				}
			}*/
		}
	}
	
	public static void clear_density(){
		for(int i=1; i<=Common.max_seg; i++){
			for(int j=0; j<split_size; j++){
				for(int k=0; k<split_size; k++){
					Common.geohash_counter[i][j][k] = 0;
				}
			}
		}
	}
}
