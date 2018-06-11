package realtraffic.gps;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;


public class Sample implements Comparable<Sample>,Serializable{


	/**
	 * 
	 */
	private static final long serialVersionUID = -3061399877199594553L;
	public long suid;
	public Date utc;
	public double lat;
	public double lon;
	public int head;
	public double speed;
	public long distance;
	public double min_matching_distance;
	public int stop; //0 stands for movement, 1 stands for long stop, 2 stands for temporary stop;
	public double moving_distance;
	
	public String date;
	
	public int gid=-1;
	public double offset=-1;
	public String route;
	
	//车辆是否可以分布式更新路况，1可以，0不可以
	public int distributed = -1;
	
	
	//for experiment
	public long start_time;
	
	public boolean passenger; //false has no passenger and true has passenager
	//for experiment
	public int x_idx;
	public int y_idx;
	
	public Sample(String date, long suid, long utc, long lat, long lon, int head, boolean passenger){
		this.date = date;
		this.suid=suid;
		this.utc=new Date(utc*1000L);
		this.lat=lat/100000.0;
		this.lon=lon/100000.0;
		this.head=head;
		this.passenger = passenger;
		this.min_matching_distance=-1.0;
		this.stop=0;
		this.moving_distance=-1;
	}
	public Sample(String date, long suid, long utc, int gid, double offset, String route,int distributed){
		this.date = date;
		this.suid=suid;
		this.utc=new Date(utc*1000L);
		this.gid=gid;
		this.offset=offset;
		this.route = route;
		this.distributed=distributed;
	}
	public Sample(String date, long suid, long utc, double lat, double lon, int head, boolean passenger){
		this.date = date;
		this.suid=suid;
		this.utc=new Date(utc*1000L);
		this.lat=lat;
		this.lon=lon;
		this.head=head;
		this.passenger = passenger;
		this.min_matching_distance=-1.0;
		this.stop=0;
		this.moving_distance=-1;
	}
	
	public String toString(){
		String output="("+suid+",";	
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		TimeZone zone=TimeZone.getTimeZone("GMT+8");
		format.setTimeZone(zone);
		output+=format.format(this.utc)+")	";		
		output+="lat:" + lat + ",lon:" + lon;	
		output+=",gid:" + gid;
		return output;
	}

	public int compareTo(Sample a){
		return this.utc.compareTo(a.utc);
	}
	
	
}
