package realtraffic.main;

import realtraffic.common.Common;
import realtraffic.gps.Sample;

import realtraffic.process.TaxiInfo;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;

/** 
 * 2016年3月11日 
 * TravelTime.java 
 * author:ZhangYu
 */

public class RealTravelTime {

	private static Connection con = null;
	private static Statement stmt = null;
	private static ResultSet rs = null;
	private static String SamplePath = "/mnt/freenas/taxi_data/0407~0430/";
	/**
	 * @param args
	 * @throws SQLException 
	 * @throws InterruptedException 
	 * @throws FileNotFoundException 
	 */
	public static void main(String[] args) throws SQLException, InterruptedException, FileNotFoundException {
		
		Common.logger.debug("start!");
		//Common.Date_Suffix = (new SimpleDateFormat("_yyyy_MM_dd")).format(new java.util.Date());
		String[] date_list = {"_2010_04_07", "_2010_04_08", "_2010_04_09", "_2010_04_10", "_2010_04_11",
				"_2010_04_12", "_2010_04_13", "_2010_04_14", "_2010_04_15", "_2010_04_16", "_2010_04_17",
				 "_2010_04_18", "_2010_04_19", "_2010_04_20", "_2010_04_21", "_2010_04_22", "_2010_04_23",
				 "_2010_04_24", "_2010_04_25", "_2010_04_26", "_2010_04_27", "_2010_04_28", "_2010_04_29"};
		
		Common.init(40000);//initialize map, matchers and process thread
		Common.init_roadlist();//initialize roadmap and initial state
		
		Common.init_overpassRoad();
		Common.init_extract_ways();
		//Common.init_node_edge();//初始化连接点和与其相连的边
		try{
			con = Common.getConnection();
			if (con == null) {
				Common.logger.error("Failed to make connection!");
				return;
			}
			stmt = con.createStatement();
				
		/*for(int i=0; i< date_list.length; i++){		
				Common.logger.debug("start to process data from " + date_list[i]);
				//if you need to restore, you should set start_counter to control where to start
				data_emission(date_list[i], -1);
				
				//Common.store(date_list[i]);
				Common.store();
			}*/
			
			//跑3天的，准备取第2天的数据
			for(int i=6; i< 9; i++){		
				Common.logger.debug("start to process data from " + date_list[i]);
				//if you need to restore, you should set start_counter to control where to start
				data_emission(date_list[i], -1);
				//Common.store();
			}
			Thread.sleep(10*60*1000);
			
			//flush updater records
			Common.real_traffic_updater.update_all_batch();
			Common.history_traffic_updater.update_all_batch();
		
			Common.logger.debug("all done.");
			
		}
		catch (Exception e) {
		    e.printStackTrace();
		    Common.logger.error("generate gps point error!");
		}
		finally{
			con.commit();
		}
	}
	
	public static void data_emission(String date, int start_counter) throws SQLException, InterruptedException{
		//drop table if exists
		String sql = "";
		try{
			sql = "drop table if exists " + Common.ValidSampleTable + ";";
			stmt.executeUpdate(sql);
		}
		catch (SQLException e) {
		    e.printStackTrace();
		    con.rollback();
		}
		finally{
			con.commit();
		}
		
		//import sample table
		try{
			import_table(SamplePath + "valid" + date);
		}
		catch(Exception e){
			Common.logger.debug("import sample data failed");
		}

		sql = "select min(utc),max(utc) from " + Common.ValidSampleTable + ";";
		rs = stmt.executeQuery(sql);
		if(rs.next()){
			Common.start_utc = rs.getLong(1);
			Common.end_utc = rs.getLong(2);
		}
		//4月14号的起始时间
		//Common.start_utc = 1271174400;
		Common.logger.debug(date + " start utc: " + Common.start_utc + "; end utc: " + Common.end_utc);
		
		//create traffic table建当天的速度表和转向表
		Common.Date_Suffix = date;
		Common.init_traffic_table();
		//clear previous travel table 删除特定日期的速度和时间表
		//Common.clear_travel_table("_2010_02_07");
		
		SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");	
		String start_time = tempDate.format(new java.util.Date());
    	Common.logger.debug("-----Real travel time process start:	"+start_time+"-------!");
		
		Common.logger.debug("-----start simulate gps point:	-------!");
		int counter;//to control generate rate
    	if(start_counter == -1){
    		counter = Common.emission_step * Common.emission_multiple;
    	}
    	else{
    		counter = start_counter;
    	}
    	//whole day
    	sql = "select * from " + Common.ValidSampleTable + " order by utc;";
    	
		Common.logger.debug(sql);
		rs = stmt.executeQuery(sql);
		
		Common.logger.debug("select finished");
		
		boolean passenager;
		//boolean store_flag = false;
		//start process gps point
		while(rs.next()){
			long utc = rs.getLong("utc");
			//to control data emission speed
			long interval = utc - Common.start_utc;
			
			//wait until utc of gps is in the time range
			while(interval >= counter){
				counter += Common.emission_step * Common.emission_multiple;
				Thread.sleep(Common.emission_step * 1000);
				Common.logger.debug("time: " + counter);
			}
			if(rs.getString("ostdesc").contains("重车")){
				passenager = true;
			}
			else{
				passenager = false;
			}
			//utc of gps is in the time range, process the point
			Sample gps = new Sample(date, rs.getLong("suid"), rs.getLong("utc"), rs.getLong("lat"), 
		    		rs.getLong("lon"), (int)rs.getLong("head"), passenager);
			
			int suid = (int) gps.suid;
			if(Common.taxi[suid] == null){
				Common.taxi[suid] = new TaxiInfo(suid);
				int number = suid % Common.thread_number;
				Common.thread_pool[number].put_suid(suid);
			}
			Common.taxi[suid].add_gps(gps);	
			//Common.logger.debug("-----gps suid:	"+suid+"-------!");
		}
			
		String end_time = tempDate.format(new java.util.Date());
		
    	Common.logger.debug("-----Real travel time process finished:	"+end_time+"-------!");
    	Common.logger.debug("process time: " + start_time + " - " + end_time + "counter: " + counter);
	}
	
	//import sample table from file
	public static void import_table(String filePath)   
	        throws IOException, InterruptedException {  
		//psql  -f ./valid_2010_04_07.sql "dbname=taxi_data user=postgres password=***"
	    String[] shell_string = {"/bin/sh", "-c", "psql  -f " + filePath + ".sql" + " 'dbname=" + Common.DataBase 
	    		+ " user=" + Common.UserName + " password=" + Common.UserPwd + "'"};
	    Common.logger.debug("import table start");
	    //Process process = Runtime.getRuntime().exec(shell_string);
	    Runtime.getRuntime().exec(shell_string); 
	    //subprocess maybe blocked or dead lock happens and do not resturn, so need to set a timeout()
	    //the problem can be solved by http://blog.csdn.net/ericahdu/article/details/5848868, but inconvenient
	    Thread.sleep(25 * 60 * 1000);
	    //int exitValue = process.wait(30 * 60 * 1000);
	    //return exitValue;
	}  
	
}
