package realtraffic.road;

import realtraffic.common.Common;
import realtraffic.gps.Sample;
import scala.Tuple2;
import scala.Tuple3;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.spark.broadcast.Broadcast;

public class AllocationRoadsegment extends RoadSegment {
	private static final long serialVersionUID = 1L;
	public int seq;//by last updated utc,1-288
	public String date;//by last updated date

	HashMap<Integer, Double> turning_time=null;
	HashMap<Integer, Integer> turning_seq=null;
	public AllocationRoadsegment(){
		super();
		this.seq = -1;
		this.date = "unknown";
		turning_time=new HashMap<Integer, Double>();
		turning_seq = new HashMap<Integer, Integer>();
	}
	
	public AllocationRoadsegment(long gid, double max_speed, double average_speed){
		super(gid, max_speed > Common.max_speed ? Common.max_speed:max_speed, 
				average_speed < Common.min_speed ? Common.min_speed : average_speed);
		turning_time=new HashMap<Integer, Double>();
		turning_seq = new HashMap<Integer, Integer>();
		this.seq = -1;
		this.date = "unknown";
	}	
	
	synchronized public Tuple2<AllocationRoadsegment, ArrayList<String>> update_speed_sample(double speed, Sample sample,Broadcast<double[][]> default_traffic,Broadcast<double[][]> default_class_traffic){
		if(speed < 0){
			return new Tuple2<>(this,null) ;
		}
		int cur_seq = Common.get_seq(sample);
		int pre_seq = this.seq;
		if(this.gid==89184)
		{
			System.out.println("89184 -1speed "+this.avg_speed);
		}
		Tuple2<Boolean, ArrayList<String>> check = check_seq(cur_seq, sample.date,default_traffic,default_class_traffic);
		if(!check._1){
			return new Tuple2<>(this,null) ;
		}
		if(this.gid==89184)
		{
			System.out.println("89184 0speed "+this.avg_speed);
		}
		//traffic jam, slow down
		if(speed == 0){
			this.avg_speed = restrict_speed(this.avg_speed * 0.9);
			this.time = this.length/this.avg_speed;
			return new Tuple2<>(this,check._2);
		}
		else if(speed > 33.33){
			return new Tuple2<>(this,check._2);
		}
		else{
			
			speed = restrict_speed(speed);

			//smooth
			int diff_seq = get_diff_seq(pre_seq, cur_seq);
			double smooth_alpha = 0.9 - diff_seq * 0.1;
			if(smooth_alpha < 0){
				smooth_alpha = 0;
			}
			//delay sample
			else if(smooth_alpha > 0.9){
				smooth_alpha = 0.9;
			}
			if(this.gid==89184)
			{
				System.out.println("89184 1speed "+speed+" pre "+pre_seq+" cur "+cur_seq+" "+diff_seq);
			}
			if(this.gid==89184)
			{
				System.out.println("89184 2speed "+this.avg_speed+" smooth_alpha "+smooth_alpha);
			}
			this.avg_speed = restrict_speed(speed * (1-smooth_alpha) + 
					this.avg_speed * smooth_alpha);
			this.time = this.length/this.avg_speed;
			if(this.gid==89184)
			{
				System.out.println("89184 3speed "+this.avg_speed);
			}
			return new Tuple2<>(this,check._2);
		}
	}
	
	//update road time, return current seq to update turning time, -3 stands for error updating
	synchronized public Tuple3<Integer,AllocationRoadsegment, ArrayList<String>> update_time(double new_time, Sample sample,Broadcast<double[][]> default_traffic,Broadcast<double[][]> default_class_traffic){
		if(new_time <= 0){
			return new Tuple3<>(-3,this,null);
		}
		
		int cur_seq = Common.get_seq(sample);
		int pre_seq = this.seq;
		Tuple2<Boolean, ArrayList<String>> check = check_seq(cur_seq, sample.date,default_traffic,default_class_traffic);
		if(!check._1){
			//不更新
			return new Tuple3<>(-3,this,check._2);
		}
		
		//check sensed speed
		double sensed_speed = this.length/new_time;
		if(sensed_speed > 33.33){
			return new Tuple3<>(-3,this,check._2);
		}

		//smooth
		int diff_seq = get_diff_seq(pre_seq, cur_seq);
		double smooth_alpha = 0.9 - diff_seq * 0.1;
		if(smooth_alpha < 0){
			smooth_alpha = 0;
		}
		//delay sample
		else if(smooth_alpha > 0.9){
			smooth_alpha = 0.9;
		}
		
		this.avg_speed = restrict_speed(sensed_speed * (1-smooth_alpha) + 
				this.avg_speed * smooth_alpha);
		this.time = this.length / this.avg_speed;
		return new Tuple3<>(cur_seq,this,check._2);
	}
	
	public double get_turning_time(int gid){
		if(turning_time.containsKey(gid)){
			return turning_time.get(gid);
		}
		else{
			return 4;
		}
	}
	
	public int get_turning_seq(int gid){
		if(turning_seq.containsKey(gid)){
			return turning_seq.get(gid);
		}
		else{
			return 0;
		}
	}
	
	public HashMap<Integer, Double> get_all_turning_time(){
		return turning_time;
	}
	
	public HashMap<Integer, Integer> get_all_turning_seq(){
		return turning_seq;
	}
	
	//road time has been updated before, so no need to check seq
	synchronized public void update_turning_time(int gid, double new_turning_time, int cur_seq){
		if(new_turning_time <= 0){
			return;
		}
		
		//smooth
		int diff_seq = get_diff_seq(this.get_turning_seq(gid),cur_seq);
		double smooth_alpha = 0.9 - diff_seq * 0.1;
		if(smooth_alpha < 0){
			smooth_alpha = 0;
		}
		//delay sample
		else if(smooth_alpha > 0.9){
			smooth_alpha = 0.9;
		}
		
		new_turning_time = this.get_turning_time(gid) * smooth_alpha + 
				new_turning_time * (1 - smooth_alpha);
		
		turning_time.put(gid, restrict_turning_time(new_turning_time));
		turning_seq.put(gid, cur_seq);//maybe do not need to consider the problem of asynchronization

	}
	
	//check whether to update traffic and insert old traffic
	private Tuple2<Boolean,ArrayList<String>> check_seq(int cur_seq, String cur_date, Broadcast<double[][]> default_traffic, Broadcast<double[][]> default_class_traffic){
		//Common.logger.debug("cur: " + cur_seq + "old " + this.seq);
		//update traffic in last seq
		ArrayList<String> updateString= new ArrayList<String>();
		if(this.seq == -1){
			this.seq = cur_seq;
			this.date = cur_date;
			return new Tuple2<>(true,updateString);
		}
		
		//last day
		if(cur_date.compareTo(this.date) < 0){
			return new Tuple2<>(false,updateString);
		}
		
		//current sample is later than last sample
		if(cur_seq > this.seq || cur_date.compareTo(this.date) > 0){
			
			//update current seq and date
			int old_seq = this.seq;	
			this.seq = cur_seq;
			String old_date = this.date;
			if(cur_date != this.date){
				this.date = cur_date;
			}
			
			//insert old traffic to database
			try{
				//插入上一个时间片路况
				//insert road traffic
				String sql = "Insert into " + Common.real_road_slice_table + old_seq + old_date
						+ "(gid, base_gid, length, class_id, time, average_speed, is_sensed) values \n";
				sql += "(" + this.gid + ", " + this.base_gid + ", " + this.length + ", " + this.class_id + ", " 
				+ this.time + ", " + this.avg_speed + ", true);";
				
				updateString.add(sql);
				//更新历史路况
				//check whether traffic of the road exists
				//already exists, update
				double default_speed = default_traffic.value()[(int) this.gid][old_seq];
				if(default_speed > 0){
					
					double new_speed = default_speed * 0.8+ this.avg_speed * 0.2;
					double new_time = this.length / new_speed;
					sql = "Update " + Common.history_road_slice_table + old_seq
							+ " set time=" + new_time + ",average_speed=" + new_speed + " where gid=" + gid;
					updateString.add(sql);
				}
				//insert
				else{
					sql = "Insert into " + Common.history_road_slice_table + old_seq
							+ "(gid, base_gid, length, class_id, time, average_speed) values \n";
					sql += "(" + this.gid + ", " + this.base_gid + ", " + this.length + ", " + this.class_id + ", " 
					+ this.length/this.avg_speed + ", " + this.avg_speed + ");";
					updateString.add(sql);
				}
				
				//插入转向时间insert turning traffic
				HashMap<Integer, Double> turing_time = this.get_all_turning_time();
				HashMap<Integer, Integer> turing_seq = this.get_all_turning_seq();
				Set<Entry<Integer, Double>> entryset=turing_time.entrySet();
				ArrayList<String> tmp_turning_sql = new ArrayList<String>();
				int next_gid;
				for(Entry<Integer, Double> m:entryset){	
					next_gid = m.getKey();
					if(old_seq == turing_seq.get(next_gid)){
						sql = "Insert into " + Common.real_turning_slice_table + old_seq + old_date
								+ "(gid, next_gid, time) values \n";
						sql += "(" + gid + ", " + next_gid + ", " + m.getValue() + ");";
						tmp_turning_sql.add(sql);
					}
				}
				updateString.addAll(tmp_turning_sql);
				
				//上面写的其实就是下面这一个函数
				//Common.real_traffic_updater.update(old_date, (int)gid, old_seq);
				
				//插入推测路况
				//no traffic sensed in the interval
				if(get_diff_seq(cur_seq, old_seq) > 1){
					//use history traffic to infer speed
					updateString.addAll(infer_speed(old_date, old_seq, cur_seq,default_traffic,default_class_traffic));
				}
			}
			catch (SQLException e) {
				e.printStackTrace();
				return new Tuple2<>(false,updateString);
			}
			return new Tuple2<>(true,updateString);	
		}
		else if(get_diff_seq(cur_seq, this.seq) <= 1){			
			return new Tuple2<>(true,updateString);
		}
		//sample is too old
		return new Tuple2<>(false,updateString);
	}
	
	//infer traffic if no traffic sensed during intervals
	private ArrayList<String> infer_speed(String pre_date, int pre_seq, int cur_seq, Broadcast<double[][]> default_traffic, Broadcast<double[][]> default_class_traffic) throws SQLException{
		
		ArrayList<String> inferList = new ArrayList<String>();
		
		double pre_speed = default_traffic.value()[(int)gid][pre_seq];
		if(pre_speed <= 0){
			return inferList;
		}
		double correction_factor = this.avg_speed / pre_speed;
		double default_speed = 0.0;
		double infer_speed = 0.0;
		double class_speed_transverse = default_class_traffic.value()[this.class_id][pre_seq];
		double class_speed_vertical = 0.0;
		double max_speed_limit = 0.0;
		double min_speed_limit = 0.0;
		
		int max_infer_seq = pre_seq + 2;
		if(max_infer_seq > cur_seq - 1){
			max_infer_seq = cur_seq - 1;
		}
		
		for(int i=pre_seq+1; i<= max_infer_seq; i++){
			int tmp_seq = i % 288;
			
			default_speed = default_traffic.value()[(int)gid][tmp_seq];
			
			if(default_speed <= 0){
				continue;
			}
			//calculate inferred speed
			infer_speed = default_speed * correction_factor;
			class_speed_vertical = default_class_traffic.value()[this.class_id][tmp_seq];
			//avoid saltation
			//transverse comparison
			max_speed_limit = this.avg_speed + 0.8 * (i- pre_seq) * class_speed_transverse;
			min_speed_limit = this.avg_speed - 0.8 * (i- pre_seq) * class_speed_transverse;
			//vertical comparison
			if(max_speed_limit > default_speed + 0.8 * class_speed_vertical){
				max_speed_limit = default_speed + 0.8 * class_speed_vertical;
			}
			if(min_speed_limit < default_speed - 0.8 * class_speed_vertical){
				min_speed_limit = default_speed - 0.8 * class_speed_vertical;
			}			
			if(max_speed_limit <= min_speed_limit){
				continue;
			}
			
			if(infer_speed > max_speed_limit){
				infer_speed = max_speed_limit;
			}
			else if(infer_speed < min_speed_limit){
				infer_speed = min_speed_limit;
			}
			infer_speed = restrict_speed(infer_speed);

			//insert it
			String tmp_date;
			//another day
			if(tmp_seq != i ){
				tmp_date = this.date;
			}
			else{
				tmp_date = pre_date;
			}
			if(infer_speed <= 0){
				continue;
			}
			//insert road traffic
			String sql = "Insert into " + Common.real_road_slice_table + tmp_seq + tmp_date
					+ "(gid, base_gid, length, class_id, time, average_speed, is_sensed) values \n";
			sql += "(" + this.gid + ", " + this.base_gid + ", " + this.length + ", " + this.class_id 
					+ ", " + this.length/infer_speed + ", " + infer_speed + ", false);";
			inferList.add(sql);
			
			double defaultSpeed = default_traffic.value()[(int) this.gid][tmp_seq];
			if(defaultSpeed > 0){
				double new_speed = defaultSpeed * 0.8+ infer_speed * 0.2;
				double new_time = this.length / new_speed;
				sql = "Update " + Common.history_road_slice_table + tmp_seq
						+ " set time=" + new_time + ",average_speed=" + new_speed + " where gid=" + gid;
				inferList.add(sql);
			}
			//insert
			else{
				sql = "Insert into " + Common.history_road_slice_table + tmp_seq
						+ "(gid, base_gid, length, class_id, time, average_speed) values \n";
				sql += "(" + this.gid + ", " + this.base_gid + ", " + this.length + ", " + this.class_id + ", " 
				+ this.length/infer_speed + ", " + infer_speed + ");";
				inferList.add(sql);
			}
			//其实是下面这句
			//Common.real_traffic_updater.update_road(tmp_date, (int)gid, tmp_seq, infer_speed);			
		}
		return inferList;
	}
	
	//restrict speed between max and min speed
	private double restrict_speed(double speed){
		if(speed > this.max_speed){
			speed = this.max_speed;
		}
		if(speed < 1.5){
			speed = 1.5;
		}
		return speed;
	}
	
	//restrict turning time
	private double restrict_turning_time(double time){
		if(time < 0.1){
			time = 0.1;
		}
		//there is not suitable way to get max turning time
		return time;
	}
	
	private int get_diff_seq(int old_seq, int cur_seq){
		int diff_seq = cur_seq - old_seq;
		while(diff_seq < 0){
			diff_seq += 288;
		}
		return diff_seq;
	}
	
}
