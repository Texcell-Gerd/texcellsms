package CacheDB;
import CacheDB.CacheDBResult.CADBPROCPARA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public  class Client {
	private static final Logger logger = LoggerFactory.getLogger(Client.class);
	private static String TAG = "NCacheDBClient";
    static
    {
    	try
    	{
//			System.getProperty("D:\\桌面\\JNI\\GrayWolf");
    		System.loadLibrary("DualList");
    		System.loadLibrary("MemAPI");
    		System.loadLibrary("ncacheapi");
    		System.loadLibrary("libiconv");
        	System.loadLibrary("CacheDBLib");
//			System.load("D:/桌面/JNI/GrayWolf/Run/DualList.dll");
//			System.load("D:/桌面/JNI/GrayWolf/Run/MemAPI.dll");
//			System.load("D:/桌面/JNI/GrayWolf/Run/ncacheapi.dll");
//			System.load("D:/桌面/JNI/GrayWolf/Run/libiconv.dll");
//			System.load("D:/桌面/JNI/GrayWolf/Run/CacheDBLib.dll");

    	}
    	catch(Exception e)
    	{
//    		System.out.println("load dll failed");
//    		System.out.println(e.getMessage().toString());
			logger.error("load dll failed",e.getMessage().toString());
    	}
    }

    public native static long InitEnv(String sServer,int nPort,String sUsername,String sPassword, String sDB, String sPageCoding,String sDBCoding);
    public native static int Connect(long pClient);
    public native static int CloseConnect(long pClient);
    public native static int Destroy(long pClient);
    public native static String ExecProc(long pClient, String sSPName, int nParaCount,CADBPROCPARA[] pPara);
    public native static String ExecDirectly(long pClient,String sQuery);
    public native static String GetDataSetItem(long pClient );
    public native static String GetDataFromDataSet(long pClient, int nDataSetNumber, int nRowNumber);
    public native static int FreeQueryHandle(long pClient);
    public native static int GetConnectStatus(long pClient);
}
