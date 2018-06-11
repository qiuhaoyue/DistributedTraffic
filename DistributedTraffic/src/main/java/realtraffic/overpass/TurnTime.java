package realtraffic.overpass;

public class TurnTime {
	public int gid;//��ǰ��·gid
	public int next_gid;//��һ����·gid
	public TurnTime(int gid,int next_gid)
	{
		this.gid = gid;
		this.next_gid = next_gid;
	}
	public int hashCode()//����
    {
        return 1;
    }
	public boolean equals(Object obj)
    {

        if(!(obj instanceof TurnTime))
            return false;
        TurnTime p = (TurnTime)obj;
        return this.gid==p.gid && this.next_gid==p.next_gid;
    }
}
