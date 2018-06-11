package realtraffic.road;

import java.io.Serializable;

public class RoadSegment implements Serializable {
	private static final long serialVersionUID = 1L;
	public long base_gid;
	public long gid;
	public double time;
	public double length;
	public long source;
	public long target;
	public int class_id;
	public double max_speed;
	public double avg_speed;
    
    RoadSegment(){
    	avg_speed=-1.0;
    	max_speed=-1.0;
    	length=-1.0;
    }
	
	RoadSegment(long gid, double max_speed, double average_speed){
		this.gid=gid;
		this.avg_speed=average_speed;
	    this.max_speed=max_speed;
	}
}
