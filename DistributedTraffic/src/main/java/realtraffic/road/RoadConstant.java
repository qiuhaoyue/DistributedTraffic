package realtraffic.road;

import java.io.Serializable;

public class RoadConstant implements Serializable{

	/**
	 * 
	 */
	private static final long serialVersionUID = 3822060393444608559L;
	public long base_gid;
	public long gid;	
	public double length;
	public int class_id;
	public double avg_speed;
	public RoadConstant(long base_gid, double length, int class_id,double avg_speed){
		this.base_gid=base_gid;
		this.length=length;
		this.class_id=class_id;
		this.avg_speed = avg_speed;
	}
}
