package realtraffic.process;

import realtraffic.common.Common;
import java.util.ArrayList;

/** 
 * 2016��4��16�� 
 * ProcessThread.java 
 * author:ZhangYu
 */

public class ProcessThread extends Thread {

	ArrayList<Integer> suid_list = null;
	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		
		while(true){
			//Common.logger.debug("processing");
			try {
				Object[] temp_suid_list;
				while(suid_list.isEmpty()){
					//10s��
					Thread.sleep(10*1000);
				}
				synchronized(suid_list){
					temp_suid_list = suid_list.toArray();
				}
				//Common.logger.debug("thread list size: " + temp_suid_list.length);
				for(int i=0; i< temp_suid_list.length; i++){
					int suid = (int) temp_suid_list[i];
					Common.taxi[suid].process();
				}
			
				//Common.logger.debug(suid_list.toString());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	public ProcessThread(){
		this.suid_list = new ArrayList<Integer>();
		
	}
	
	synchronized public void put_suid(int suid){
		if(suid_list.contains(suid)){
			return;
		}
		synchronized(suid_list){
			suid_list.add(suid);
		}		
	}
	
	public int get_list_size(){
		return suid_list.size();
	}
}
