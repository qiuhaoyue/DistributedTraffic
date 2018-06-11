package SendData;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import realtraffic.main.StreamingMainTaxiInfo;

public class TcpServer {
	public static void main(String[] args) throws IOException
	{
		ServerSocket s1 = new ServerSocket(9999);
		//阻塞式
		Socket sGps = s1.accept();
		// 创建一个发送gps线程
	    new SendGpsData(sGps.getOutputStream()).start();
	    

	}
}
