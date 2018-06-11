package realtraffic.process;

public class ConvergeCar
{
	public int gid;//��·id
	public long suid;//����id
	public long utc;
	public double offset;
	public String route;
	public ConvergeCar(int gid,long suid,long utc,double offset,String route)
	{
		this.gid = gid;
		this.suid = suid;
		this.utc = utc;
		this.offset = offset;
		this.route = route;
	}
	public String toString() 
	{
		return gid+" "+suid+" "+utc+" "+offset+" "+route;
    }
}
