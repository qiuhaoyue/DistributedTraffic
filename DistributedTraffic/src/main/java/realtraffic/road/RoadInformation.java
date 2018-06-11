package realtraffic.road;

import java.io.Serializable;

public class RoadInformation implements Serializable 
{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 891107558976697973L;
	private long gid;
	private short class_id;
	private float length;
	private long source;
	private long target;
	public double x1;//lon1
	public double x2;//lon2
	public double y1;//lat1
	public double y2;//lat2
	public int seq;//ʱ��Ƭ
	public double speed = 0;
	public RoadInformation(int seq, long gid)
	{
		this.seq = seq;
		this.gid = gid;
	}

	
	public RoadInformation(long gid,short class_id,long source,long target,float length)
	{
		this.gid = gid;
		this.class_id = class_id;
		this.source = source;
		this.target = target;
		this.length = length;
	}
	public RoadInformation(long gid,long source,long target,double x1,double y1,double x2,double y2)
	{
		this.gid = gid;
		this.source = source;
		this.target = target;
		this.x1 = x1;
		this.y1 = y1;
		this.x2 = x2;
		this.y2 = y2;
	}
	public double getX1() {
		return x1;
	}
	public void setX1(double x1) {
		this.x1 = x1;
	}
	public double getX2() {
		return x2;
	}
	public void setX2(double x2) {
		this.x2 = x2;
	}
	public double getY1() {
		return y1;
	}
	public void setY1(double y1) {
		this.y1 = y1;
	}
	public double getY2() {
		return y2;
	}
	public void setY2(double y2) {
		this.y2 = y2;
	}
	public long getGid()
	{
		return this.gid;
	}
	public short getClassId()
	{
		return this.class_id;
	}
	public float getLength()
	{
		return this.length;
	}
	public long getSource()
	{
		return this.source;
	}
	public long getTarget()
	{
		return this.target;
	}
	
}
