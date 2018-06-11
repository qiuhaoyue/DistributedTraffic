package realtraffic.main;

import java.io.Serializable;

import org.apache.spark.streaming.scheduler.StreamingListener;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchCompleted;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchSubmitted;
import org.apache.spark.streaming.scheduler.StreamingListenerOutputOperationCompleted;
import org.apache.spark.streaming.scheduler.StreamingListenerOutputOperationStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverError;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStopped;

public class JobListener implements StreamingListener,Serializable{

	private static final long serialVersionUID = 1L;
	//StreamingListener参考：
	//https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.streaming.scheduler.StreamingListener
	//BatchInfo参考：
	//https://spark.apache.org/docs/latest/api/scala/index.html#org.apache.spark.streaming.scheduler.BatchInfo
	//long totalDelay;
	int num=1;
	@Override
	public void onBatchCompleted(StreamingListenerBatchCompleted batchCompleted) {
		// TODO Auto-generated method stub
		/** Called when processing of a batch of jobs has completed. */
		/*batchCompleted.batchInfo().totalDelay().get().toString();
		batchCompleted.batchInfo().processingDelay().get().toString();
		batchCompleted.batchInfo().schedulingDelay().get().toString();//调度：从提交任务到处理之前这段时间？
		batchCompleted.batchInfo().numRecords();*/
		System.out.println("batchTime " + batchCompleted.batchInfo().batchTime().toString()
                +" submissionTime " + batchCompleted.batchInfo().submissionTime()
                +" schedulingDelay " + batchCompleted.batchInfo().schedulingDelay().get().toString()
				+" processingDelay "+batchCompleted.batchInfo().processingDelay().get().toString() 
				+" totalDelay " +batchCompleted.batchInfo().totalDelay().get().toString()+" "+num);
		num++;
		//我们可以自己直接在这个batch完成时获取当前机器内存使用情况吗？如果需要的话
		//此处的totalDelay=processingDelay+schedulingDelay
		//真实的totalDelay=submissionTime-batchTime+totalDelay
		/*totalDelay = Integer.valueOf(batchCompleted.batchInfo().totalDelay().get().toString());
		processingDelay = Integer.valueOf(batchCompleted.batchInfo().processingDelay().get().toString());
		schedulingDelay = Integer.valueOf(batchCompleted.batchInfo().schedulingDelay().get().toString());*/
		
	}
	@Override
	public void onBatchStarted(StreamingListenerBatchStarted arg0) {
		// TODO Auto-generated method stub
		/** Called when processing of a batch of jobs has started.  */
	}

	@Override
	public void onBatchSubmitted(StreamingListenerBatchSubmitted arg0) {
		// TODO Auto-generated method stub
		/** Called when a batch of jobs has been submitted for processing. */
	}

	@Override
	public void onOutputOperationCompleted(StreamingListenerOutputOperationCompleted arg0) {
		// TODO Auto-generated method stub
		/** Called when processing of a job of a batch has completed. */
	}

	@Override
	public void onOutputOperationStarted(StreamingListenerOutputOperationStarted arg0) {
		// TODO Auto-generated method stub
		/** Called when processing of a job of a batch has started. */
	}

	@Override
	public void onReceiverError(StreamingListenerReceiverError arg0) {
		// TODO Auto-generated method stub
		/** Called when a receiver has reported an error */
	}

	@Override
	public void onReceiverStarted(StreamingListenerReceiverStarted arg0) {
		// TODO Auto-generated method stub
		/** Called when a receiver has been started */
	}

	@Override
	public void onReceiverStopped(StreamingListenerReceiverStopped arg0) {
		// TODO Auto-generated method stub
		/** Called when a receiver has been stopped */
	}

}
