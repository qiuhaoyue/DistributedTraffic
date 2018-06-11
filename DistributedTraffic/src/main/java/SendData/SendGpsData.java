package SendData;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;

import realtraffic.gps.Sample;
import realtraffic.common.Common;

public class SendGpsData extends Thread{
	
	private static Connection con = null;
	private static Statement stmt = null;
	private static ResultSet rs = null;
	private static String SamplePath = "/mnt/freenas/taxi_data/0407~0430/";
	
	private PrintStream out;
    public SendGpsData(OutputStream output) {
    	this.out = new PrintStream(output,true);
    }
    public void run(){
    	
    	Common.logger.debug("start!");
		String[] date_list = {"_2010_04_07", "_2010_04_08", "_2010_04_09", "_2010_04_10", "_2010_04_11",
				"_2010_04_12", "_2010_04_13", "_2010_04_14", "_2010_04_15", "_2010_04_16", "_2010_04_17",
				 "_2010_04_18", "_2010_04_19", "_2010_04_20", "_2010_04_21", "_2010_04_22", "_2010_04_23",
				 "_2010_04_24", "_2010_04_25", "_2010_04_26", "_2010_04_27", "_2010_04_28", "_2010_04_29"};
		try{
			con = Common.getConnection();
			if (con == null) {
				Common.logger.error("Failed to make connection!");
				return;
			}
			stmt = con.createStatement();
			
			//跑1天的，准备取第1天的数据
			for(int i=7; i< 8; i++){		
				Common.logger.debug("start to process data from " + date_list[i]);
				data_emission(date_list[i], -1, out);
			}
			//Thread.sleep(10*60*1000);
			
		/*	//flush updater records
			Common.real_traffic_updater.update_all_batch();
			Common.history_traffic_updater.update_all_batch();
		*/
			Common.logger.debug("all done.");
			
		}
		catch (Exception e) {
		    e.printStackTrace();
		    Common.logger.error("generate gps point error!");
		}
		finally{
			try {
				con.commit();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
    }
    public static void data_emission(String date, int start_counter,PrintStream out) throws SQLException, InterruptedException{
		//drop table if exists
		String sql = "";
		/*
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
		
		*/
		sql = "select min(utc),max(utc) from " + Common.ValidSampleTable + ";";
		rs = stmt.executeQuery(sql);
		if(rs.next()){
			Common.start_utc = rs.getLong(1);
			Common.end_utc = rs.getLong(2);
		}
		//4月14号的起始时间
		//Common.start_utc = 1271174400;
		
		//create traffic table建当天的速度表和转向表
		Common.Date_Suffix = date;
		//Common.init_traffic_table();
		
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
    	//10min
    	sql = "select * from " + Common.ValidSampleTable + " where utc <= 1271176400 order by utc;";
    	// and suid=16414
    	Common.logger.debug(sql);
		rs = stmt.executeQuery(sql);	
		Common.logger.debug("select finished");
		
		boolean passenger;
		
		//start process gps point
		String line;
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
				passenger = true;
			}
			else{
				passenger = false;
			}
			
			/*
			//utc of gps is in the time range, process the point
			Sample gps = new Sample(date, rs.getLong("suid"), rs.getLong("utc"), rs.getLong("lat"), 
		    		rs.getLong("lon"), (int)rs.getLong("head"), passenger);
			
			int x_idx = (int)((gps.lon - Common.min_lon) / Common.lon_interval);
			if(x_idx == Common.split_size){
				x_idx -= 1;
			}
			int y_idx = (int)((gps.lat - Common.min_lat) / Common.lat_interval);
			if(y_idx == Common.split_size){
				y_idx -= 1;
			}
			if(x_idx < 0 || y_idx < 0 || x_idx > Common.split_size || y_idx > Common.split_size){
				continue;
			}
			//获取所属区号
			gps.x_idx = x_idx;
			gps.y_idx = y_idx;*/
			line = date+ " " + rs.getLong("suid") + " " + rs.getLong("utc") + " " + rs.getLong("lat") + " "
			+ rs.getLong("lon") + " " +rs.getLong("head") + " " + passenger/*+ " " + gps.x_idx + " " + gps.y_idx*/;
			System.out.print(line);
			out.println(line);
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
