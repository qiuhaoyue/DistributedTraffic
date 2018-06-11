package realtraffic.main;
/**
 * 暂时废弃
 * */
import org.apache.spark.Partitioner;
//分区器
public class JavaCustomPart extends Partitioner{
	private static final long serialVersionUID = 1L;
	int i = 1;
    public JavaCustomPart(int i){
        this.i=i;
    }
	@Override
	public int getPartition(Object key) {
		int keyCode = Integer.parseInt(key.toString());
        return keyCode%i;
	}

	@Override
	public int numPartitions() {
		return i;
	}

}
