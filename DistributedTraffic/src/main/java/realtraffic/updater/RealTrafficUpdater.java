package realtraffic.updater;

import realtraffic.common.Common;
import realtraffic.road.AllocationRoadsegment;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;

import org.postgresql.util.PSQLException;

/** 
 * 2016��5��27�� 
 * RealTrafficUpdater.java 
 * author:ZhangYu
 */

//update traffic time and turning time
public class RealTrafficUpdater extends Timer{
	
	private Connection con = null;
	private Statement stmt = null;
	//private ArrayList<String> road_updater_list = null;
	//private ArrayList<String> turning_updater_list = null;
	private ArrayList<String> updater_list = null;
	private int max_update_num = 5000;
	//private static Lock lock = null;
	public RealTrafficUpdater(){
		con = Common.getConnection();
		updater_list = new ArrayList<String>();
		//turning_updater_list = new ArrayList<String>();
		//con.setAutoCommit(false);
		try{
			stmt = con.createStatement();
		}
		catch (SQLException e) {
		    e.printStackTrace();
		}
	}
	
	public void create_traffic_table(String date) throws SQLException{
		
		//create all slice table
		for(int i=1; i<= Common.max_seg; i++){
			try{
				//road time
				String road_slice_table = Common.real_road_slice_table + i + date;
				String sql = "DROP TABLE IF EXISTS " + road_slice_table + ";";
				stmt.executeUpdate(sql);
				//create slice table
				sql = "CREATE TABLE " + road_slice_table + "(gid integer, base_gid integer, length integer,"
						+ " class_id integer, time double precision, average_speed double precision, is_sensed boolean);";
				Common.logger.debug(sql);
				stmt.executeUpdate(sql);
				
				//turning time
				String turning_slice_table = Common.real_turning_slice_table + i + date;
				sql = "DROP TABLE IF EXISTS " + turning_slice_table + ";";
				stmt.executeUpdate(sql);
				//create slice table
				sql = "CREATE TABLE " + turning_slice_table + "(gid integer, next_gid integer,"
						+ " time double precision);";
				Common.logger.debug(sql);
				stmt.executeUpdate(sql);
			}
			catch (SQLException e) {
			    e.printStackTrace();
			    con.rollback();
			}
			finally{
				con.commit();
			}
		}
	}
	public boolean update(String date, int gid, int seq) throws SQLException{
		try{
			//insert road traffic
			String sql = "Insert into " + Common.real_road_slice_table + seq + date
					+ "(gid, base_gid, length, class_id, time, average_speed, is_sensed) values \n";
			AllocationRoadsegment road = Common.roadlist[gid];
			sql += "(" + road.gid + ", " + road.base_gid + ", " + road.length + ", " + road.class_id + ", " 
			+ road.time + ", " + road.avg_speed + ", true);";
			
			synchronized(updater_list){
				updater_list.add(sql);
				if(updater_list.size() > max_update_num){
					update_batch();
					updater_list.clear();
				}
			}
			
			//update history traffic
			Common.history_traffic_updater.update(gid, seq, road.avg_speed);
			
			//insert turning traffic
			HashMap<Integer, Double> turing_time = road.get_all_turning_time();
			HashMap<Integer, Integer> turing_seq = road.get_all_turning_seq();
			Set<Entry<Integer, Double>> entryset=turing_time.entrySet();
			ArrayList<String> tmp_turning_sql = new ArrayList<String>();
			int next_gid;
			for(Entry<Integer, Double> m:entryset){	
				next_gid = m.getKey();
				if(seq == turing_seq.get(next_gid)){
					sql = "Insert into " + Common.real_turning_slice_table + seq + date
							+ "(gid, next_gid, time) values \n";
					sql += "(" + gid + ", " + next_gid + ", " + m.getValue() + ");";
					tmp_turning_sql.add(sql);
				}
			}
			
			synchronized(updater_list){
				updater_list.addAll(tmp_turning_sql);
				if(updater_list.size() > max_update_num){
					update_batch();
					updater_list.clear();
				}
			}
			
		}
		catch (PSQLException e) {
			Common.logger.debug("update traffic failed");
		    e.printStackTrace();
		    con.rollback();
		    return false;
		}
		catch (SQLException e) {
			Common.logger.debug("update traffic failed");
		    e.printStackTrace();
		    con.rollback();
		    return false;
		}
		
		finally{
			con.commit();
		}
		return true;
	}
	
	//update inferred traffic
	public boolean update_road(String date, int gid, int seq, double speed) throws SQLException{
		try{
			if(speed <= 0){
				return false;
			}
			//insert road traffic
			String sql = "Insert into " + Common.real_road_slice_table + seq + date
					+ "(gid, base_gid, length, class_id, time, average_speed, is_sensed) values \n";
			AllocationRoadsegment road = Common.roadlist[gid];
			sql += "(" + road.gid + ", " + road.base_gid + ", " + road.length + ", " + road.class_id 
					+ ", " + road.length/speed + ", " + speed + ", false);";
			//Common.logger.debug(sql);
			synchronized(updater_list){
				updater_list.add(sql);
				if(updater_list.size() > max_update_num){
					update_batch();
					updater_list.clear();
				}
			}
			//update history traffic
			Common.history_traffic_updater.update(gid, seq, speed);
			
		}
		catch (PSQLException e) {
			Common.logger.debug("update traffic failed");
		    e.printStackTrace();
		    con.rollback();
		    return false;
		}
		catch (SQLException e) {
			Common.logger.debug("update traffic failed");
		    e.printStackTrace();
		    con.rollback();
		    return false;
		}
		finally{
			con.commit();
		}
		return true;
	}
	
	//the below two method must be called in "synchronized" block
	private void update_batch() throws SQLException{
		for(String sql:updater_list){
			stmt.addBatch(sql);
		}
		stmt.executeBatch();
		stmt.clearBatch();
	}
	
	
	//flush all update records, called when procedure finished or store current state
	public void update_all_batch() throws SQLException{
		//flush records
		synchronized(updater_list){
			update_batch();
		}
		updater_list.clear();
	}
	
	public int get_batch_size(){
		return updater_list.size();
	}
}
