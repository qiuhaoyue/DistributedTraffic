package SendData;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import realtraffic.gps.Sample;

public class StreamingWordCount {
	public static void main(String[] args) throws InterruptedException, FileNotFoundException{
		PrintStream ps=new PrintStream(new FileOutputStream("/home/qhyue/WordCount.txt"));
		System.setOut(ps);
		SparkConf conf = new SparkConf().setAppName("wordcount").setMaster("local[13]");
		// 该对象除了接受SparkConf对象,还要接受一个Batch Interval参数,就是说,每收集多长时间数据划分一个batch去进行处理
		// Durations里面可以设置分钟、毫秒、秒,这里设置1秒
		JavaStreamingContext jssc = new JavaStreamingContext(conf,Durations.seconds(1));
		
		// 首先创建输入DStream,代表一个数据源比如从socket或kafka来持续不断的进入实时数据流
		// 创建一个监听端口的socket数据流,这里面就会有每隔一秒生成一个RDD,RDD的元素类型为String就是一行一行的文本
		JavaReceiverInputDStream<String> lines = jssc.socketTextStream("localhost", 9999);
		//每秒的GPS信息
		JavaDStream<Sample> gps = lines.map(new Function<String,Sample>(){
			private static final long serialVersionUID = 1L;

			@Override
			public Sample call(String v1) throws Exception {
				String date = v1.split(" ")[0];
				long suid = Long.parseLong(v1.split(" ")[1]);
				long utc = Long.parseLong(v1.split(" ")[2]);
				long lat = Long.parseLong(v1.split(" ")[3]);
				long lon = Long.parseLong(v1.split(" ")[4]);
				int head = (int) Long.parseLong(v1.split(" ")[5]);
				boolean passenger = Boolean.parseBoolean(v1.split(" ")[6]);
				Sample gps = new Sample(date, suid, utc, lat, lon, head, passenger);
				return gps;
			}
		});

		// 最后每次计算完,都打印一下这1秒钟的gps信息
		gps.foreachRDD(new VoidFunction<JavaRDD<Sample>>()
				{
					private static final long serialVersionUID = 1L;
					@Override
					public void call(JavaRDD<Sample> t) throws Exception {

						for(Sample s : t.collect())
						{
							System.out.println(s.suid+" "+s.lat+" "+s.lon+" "+s.head+" "+s.passenger+" "+s.utc.getTime()/1000);
						}
						//每秒进来的车辆数据
						//System.out.println("======================");
					}
			
				});
		// 必须调用start方法,整个spark streaming应用才会启动执行,然后卡在那里,最后close释放资源
		jssc.start();
		jssc.awaitTermination();
		jssc.close();
	}
}
