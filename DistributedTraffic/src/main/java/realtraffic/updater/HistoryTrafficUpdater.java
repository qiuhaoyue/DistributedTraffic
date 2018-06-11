/** 
* 2016��8��17�� 
* HistoryTrafficUpdater.java 
* author:ZhangYu
*/ 
package realtraffic.updater;

import realtraffic.common.Common;
import realtraffic.road.AllocationRoadsegment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

import org.postgresql.util.PSQLException;

public class HistoryTrafficUpdater {
	private Connection con = null;
	private Statement stmt = null;
	private ArrayList<String> updater_list = null;
	private int max_update_num = 5000;
	
	public HistoryTrafficUpdater() throws SQLException{
		con = Common.getConnection();
		updater_list = new ArrayList<String>();
		try{
			stmt = con.createStatement();
			String sql = "select count(*) from pg_class where relname = '" + Common.history_road_slice_table + "1';";
			Common.logger.debug(sql);
			ResultSet rs = stmt.executeQuery(sql);
			if(rs.next()){
				int count = rs.getInt(1);
				//table not exists, create history traffic table
				if(count == 0){
					//create all slice table
					for(int i=1; i<= Common.max_seg; i++){
						//road time
						String road_slice_table = Common.history_road_slice_table + i;
						//create slice table
						sql = "CREATE TABLE " + road_slice_table + "(gid integer, base_gid integer, length integer,"
								+ " class_id integer, time double precision, average_speed double precision);";
						Common.logger.debug(sql);
						stmt.executeUpdate(sql);
						
						//turning time
						/*String turning_slice_table = Common.history_turning_slice_table + i;
						sql = "DROP TABLE IF EXISTS " + turning_slice_table + ";";
						stmt.executeUpdate(sql);
						//create slice table
						sql = "CREATE TABLE " + turning_slice_table + "(gid integer, next_gid integer,"
								+ " time double precision);";
						Common.logger.debug(sql);
						stmt.executeUpdate(sql);*/
						
						//create index
						sql = "CREATE INDEX gid_idx_" + i + " ON " + road_slice_table + "(gid)";
						Common.logger.debug(sql);
						stmt.executeUpdate(sql);
					}
					
				}
			}
		}
		catch (PSQLException e) {
			Common.logger.debug("create history traffic failed");
		    e.printStackTrace();
		    con.rollback();
		}
		catch (SQLException e) {
			Common.logger.debug("create history traffic failed");
		    e.printStackTrace();
		    con.rollback();
		}
		finally{
			con.commit();
		}
		
	}
	
	public boolean update(int gid, int seq, double speed) throws SQLException{
		try{
			//check whether traffic of the road exists
			//already exists, update
			if(Common.default_traffic[gid][seq] > 0){
				AllocationRoadsegment road = Common.roadlist[gid];
				double new_speed = Common.default_traffic[gid][seq] * 0.8
						+ speed * 0.2;
				double new_time = road.length / new_speed;
				String sql = "Update " + Common.history_road_slice_table + seq
						+ " set time=" + new_time + ",average_speed=" + new_speed + " where gid=" + gid;
				
				//Common.logger.debug(sql);
				synchronized(updater_list){
					updater_list.add(sql);
					if(updater_list.size() > max_update_num){
						update_batch();
						updater_list.clear();
					}
				}
			}
			//insert
			else{
				insert(gid, seq, speed);
			}
			
		}
		catch (PSQLException e) {
			Common.logger.debug("update history traffic failed");
		    e.printStackTrace();
		    con.rollback();
		    return false;
		}
		catch (SQLException e) {
			Common.logger.debug("update history traffic failed");
		    e.printStackTrace();
		    con.rollback();
		    return false;
		}
		finally{
			con.commit();
		}
		return true;
	}
	
	public boolean insert(int gid, int seq, double speed) throws SQLException{
		try{
			//insert road traffic
			String sql = "Insert into " + Common.history_road_slice_table + seq
					+ "(gid, base_gid, length, class_id, time, average_speed) values \n";
			AllocationRoadsegment road = Common.roadlist[gid];
			sql += "(" + road.gid + ", " + road.base_gid + ", " + road.length + ", " + road.class_id + ", " 
			+ road.length/speed + ", " + speed + ");";
			//Common.logger.debug(sql);
			synchronized(updater_list){
				updater_list.add(sql);
				if(updater_list.size() > max_update_num){
					update_batch();
					updater_list.clear();
				}
			}
			
		}
		catch (SQLException e) {
		    e.printStackTrace();
		    con.rollback();
		    return false;
		}
		finally{
			con.commit();
		}
		return true;
	}
	
	//this method must be called in "synchronized" block
	private void update_batch() throws SQLException{
		for(String sql:updater_list){
			stmt.addBatch(sql);
		}
		stmt.executeBatch();
		stmt.clearBatch();
	}
	
	//flush all update records, called when procedure finished or store current state
	public void update_all_batch() throws SQLException{
		synchronized(updater_list){
			update_batch();
		}
		updater_list.clear();
	}
	
	public int get_batch_size(){
		return updater_list.size();
	}
}
