package CacheDB;
import com.google.gson.Gson;
import CacheDB.CacheDBResult.*;
import com.texcellsms.sms.interceptor.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

//@Service
public class NCacheDBLink {
	private static  List<NCacheDBLink> plist =new CopyOnWriteArrayList();

	private static final Logger logger = LoggerFactory.getLogger(NCacheDBLink.class);

	private static final int connections = 10;

	private static final String sServer ="192.168.128.250";

	private static int nPort = 11781;

	private static String sUserName = "example";

	private static String sPassword ="example";

	private static String sDB ="example";

	private static String sPageCoding ="utf-8";

	private static String sDBCoding ="utf-8";

	public  static NCacheDBLink getNCacheDBLink(){
//		if (plist.isEmpty()||plist==null){
//			 addlist();
//		}
//		for (int i = 0; i <plist.size() ; i++) {
//			if (plist.get(i).GetConnectStatus()!= 0){
//				return plist.get(i);
//			}
//		}
		return  new NCacheDBLink();
	}
	private NCacheDBLink(){};

	private long pClient = 0;

	private static void addlist(){
		for (int j = 0; j < connections; j++) {
			plist.add(new NCacheDBLink());
		}
	}
	public synchronized int  InitEnv(String sServer,int nPort,String sUserName,String sPassword,String sDB, String sPageCoding,String sDBCoding)
	{
		pClient = Client.InitEnv(sServer,nPort,sUserName,sPassword,sDB,sPageCoding,sDBCoding);
		if( pClient != 0 )
		{
//			System.out.println("Init OK!");
			logger.info("CacheDB初始化成功:sServer: {} nPort: {} sUserName: {} sPassword: {} sDB: {}",sServer,nPort,sUserName,sPassword,sDB);
		}
		else
		{
//			System.out.println("Init Fail!");
			logger.error("CacheDB初始化失敗:sServer: {} nPort: {} sUserName: {} sPassword: {} sDB: {} sPageCoding: {} sDBCoding: {}",sServer,nPort,sUserName,sPassword,sDB,sPageCoding,sDBCoding);
		}
		return 0;
	}


	public synchronized int InitEnv(){
		pClient = Client.InitEnv(sServer,nPort,sUserName,sPassword,sDB,sPageCoding,sDBCoding);
		if( pClient != 0 )
		{
			//System.out.println("Init OK!");
			logger.info("CacheDB初始化成功:sServer: {} nPort: {} sUserName: {} sPassword: {} sDB: {} ",sServer,nPort,sUserName,sPassword,sDB);
		}
		else
		{
//			System.out.println("Init Fail!");
			logger.error("CacheDB初始化失敗:sServer: {} nPort: {} sUserName: {} sPassword: {} sDB: {} sPageCoding: {} sDBCoding: {}",sServer,nPort,sUserName,sPassword,sDB,sPageCoding,sDBCoding);
		}
		return 0;
	}


	public synchronized int ConnectToCacheDB()
	{
		if( pClient == 0 )
		{
//			System.out.println("Not Init!");
			logger.error("连接未初始化");
			return 1;
		}
		int nRet =  Client.Connect(pClient);
		logger.info("连接结果: {}",nRet);
//		System.out.println("Connect Result:" + nRet);
		return nRet;
	}

	public synchronized int Disconnect()
	{
		if( pClient == 0 )
		{
//			System.out.println("Not Init!");
			logger.error("连接未初始化");
			return 1;
		}
		int nRet =  Client.CloseConnect(pClient);
//		System.out.println("Disconnect Result:" + nRet);
		logger.info("断开结果: {}",nRet);
		return nRet;
	}

	public synchronized void Destroy()
	{
		if( pClient == 0 )
		{
//			System.out.println("Not Init!");
			logger.error("连接未初始化");
		}
		int nRet =  Client.Destroy(pClient);
		//pClient = 0;
//		System.out.println("Destroy Result:" + nRet);
		logger.info("销毁结果: {}",nRet);
	}

	public Map<String, Object> prc_MMSIFQueryBalance(String sSubAccount){

		String strSQL ="prc_MMSIFQueryBalance";
		int nParaCount = 4;
		//帐号
		CADBPROCPARA[] pPara = new CADBPROCPARA[4];
		pPara[0] = new CADBPROCPARA();

		pPara[0].nDataType = 12;//GString
		pPara[0].nParaType = 0;//input
		pPara[0].sName = "UserName";
		pPara[0].sInput = sSubAccount;
		pPara[0].nSize = pPara[0].sInput.length();
		pPara[0].sOutput = "";

		//存储过程结果
		pPara[1] = new CADBPROCPARA();
		pPara[1].nDataType = 12;//GString
		pPara[1].nParaType = 1;//input
		pPara[1].sName = "Result";
		pPara[1].sInput = "";
		pPara[1].nSize = 1;
		pPara[1].sOutput = "";
		//余额，必须除以10000
		pPara[2] = new CADBPROCPARA();

		pPara[2].nDataType = 12;//GString
		pPara[2].nParaType = 1;//input
		pPara[2].sName = "Balance";
		pPara[2].sInput = "";
		pPara[2].nSize = 1;
		pPara[2].sOutput = "";

		//信用额度,必须除以10000
		pPara[3] = new CADBPROCPARA();

		pPara[3].nDataType = 12;//GString
		pPara[3].nParaType = 1;//input
		pPara[3].sName = "Credit";
		pPara[3].sInput = "";
		pPara[3].nSize = 1;
		pPara[3].sOutput = "";
		String pResultS = Client.ExecProc(pClient, strSQL, nParaCount, pPara);
		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(pResultS, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		logger.info(strSQL+"调用开始 用户: {}",sSubAccount);
		logger.info("Ret={} sError={} outputCount={} totaldataset:{}", pResult.getnRet(),decodeErr, pResult.getnOutputParaCount() , pResult.getnTotalDataSet());
		//System.out.println("Ret=" + pResult.getnRet() + ",sError=" + decodeErr + ",outputCount=" + pResult.getnOutputParaCount() + ",totaldataset:" + pResult.getnTotalDataSet());
		List<Recordset> pOutVal = pResult.getOutputPara();
		Map<String, Object> map = new HashMap<String, Object>();
		if( pOutVal.size() > 0 )
		{
			for( int i = 0; i < pOutVal.size(); i++ )
			{
				byte [] sVal = Base64Util.decode(pOutVal.get(i).getFieldValue());
				String strVal = new String(sVal);
//				System.out.println(pOutVal.get(i).getFieldName() + ":" + strVal );
				logger.info("KEY:{} Val:{}",pOutVal.get(i).getFieldName(),strVal);
				map.put(pOutVal.get(i).getFieldName(),strVal);
			}
		}
		map.put("sError",decodeErr);
		logger.info(strSQL+"调用结束 用户: {}",sSubAccount);
		return map;
	}


	public List<Map<String,String>> web_QueryUserRate(String strUserName,String MCC,String MNC){

		List<Map<String,String>> list =new ArrayList<>();

		String strSQL ="web_QueryUserRate";
		int nParaCount = 2;
		CADBPROCPARA[] pPara = new CADBPROCPARA[2];
		//帐号
		pPara[0] = new CADBPROCPARA();
		pPara[0].nDataType = 12;//GString
		pPara[0].nParaType = 0;//input
		pPara[0].sName = "Account";
		pPara[0].sInput = strUserName;
		pPara[0].nSize = pPara[0].sInput.length();
		pPara[0].sOutput = "";

		//指定的MCCMNC，获取全部用 %
		pPara[1] = new CADBPROCPARA();

		pPara[1].nDataType = 12;//GString
		pPara[1].nParaType = 0;//input
		pPara[1].sName = "VendorId";
		if (MCC.equals("")||MCC==null){
			pPara[1].sInput = "%";
		}else {
			pPara[1].sInput = MCC+MNC;
		}
		pPara[1].nSize = pPara[1].sInput.length();
		pPara[1].sOutput = "";
		String pResultS = Client.ExecProc(pClient, strSQL, nParaCount, pPara);
		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(pResultS, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		logger.info(strSQL+"调用开始 用户: {}",strUserName);
		logger.info("Ret={} sError={} outputCount={} totaldataset:{}", pResult.getnRet(),decodeErr, pResult.getnOutputParaCount() , pResult.getnTotalDataSet());
		List<Recordset> pOutVal = pResult.getOutputPara();
		if( pOutVal.size() > 0 )
		{
			for( int i = 0; i < pOutVal.size(); i++ )
			{
				byte [] sVal = Base64Util.decode(pOutVal.get(i).getFieldValue());
				String strVal = new String(sVal);
				//System.out.println(pOutVal.get(i).getFieldName() + ":" + strVal );
				logger.info("KEY:{} Val:{}",pOutVal.get(i).getFieldName(),strVal);
			}
		}
		if( pResult.getnTotalDataSet() > 0 )
		{
			pResultS = Client.GetDataSetItem(pClient);
			RecordsetSummary pSummary = parser.fromJson(pResultS, RecordsetSummary.class);
			if( pSummary.getRet() > -90)
			{
				byte [] bErr = Base64Util.decode(pSummary.getsError());
				decodeErr = new String(bErr);
			}
			else
			{
				decodeErr = pSummary.getsError();
			}
			List<RecordSetItems> pItem = pSummary.getDataSet();
//			System.out.println("---------------ExecProc_InAndOutAndRecordSet_WithUnicode GetSummary----------");
//			System.out.println("Ret=" + pSummary.getRet() + ",sError=" + decodeErr);
			for( int j = 0; j < pItem.size(); j++ )
			{
				logger.info("DataSet: {} Rows:{} ColumnCount is:{}",pItem.get(j).getDataSetNumber(),pItem.get(j).getnRowCount(),pItem.get(j).getnColumnCount());
				//System.out.println("DataSet:" + pItem.get(j).getDataSetNumber() + ",Rows:" + pItem.get(j).getnRowCount() + ",ColumnCount is:" + pItem.get(j).getnColumnCount());
				list = GetDataSetItem(pItem.get(j).getnRowCount(),pItem.get(j).getDataSetNumber());
			}
//			System.out.println("---------------ExecProc_InAndOutAndRecordSet_WithUnicode GetSummary end------");
		}
		logger.info(strSQL+"调用结束 用户:{}",strUserName);
//		System.out.println("---------------ExecProc_InAndOutAndRecordSet_WithUnicode  End----------");
		return list;
	}

	public Map<String, Object> prc_ChangeSwitchAccountPWD(String strUserName,String strOldPass,String strNewPass){
		String strSQL ="prc_ChangeSwitchAccountPWD";
		int nParaCount = 4;
		//帐号
		CADBPROCPARA[] pPara = new CADBPROCPARA[4];
		pPara[0] = new CADBPROCPARA();
		pPara[0].nDataType = 12;//GString
		pPara[0].nParaType = 0;//input
		pPara[0].sName = "Account";
		pPara[0].sInput = strUserName;
		pPara[0].nSize = pPara[0].sInput.length();
		pPara[0].sOutput = "";

		//旧密码
		pPara[1] = new CADBPROCPARA();

		pPara[1].nDataType = 12;//GString
		pPara[1].nParaType = 0;//input
		pPara[1].sName = "Oldpass";
		pPara[1].sInput = strOldPass;
		pPara[1].nSize = pPara[1].sInput.length();
		pPara[1].sOutput = "";

		//新密码
		pPara[2] = new CADBPROCPARA();
		pPara[2].nDataType = 12;//GString
		pPara[2].nParaType = 0;//input
		pPara[2].sName = "Newpass";
		pPara[2].sInput = strNewPass;
		pPara[2].nSize = pPara[2].sInput.length();
		pPara[2].sOutput = "";

		//结果
		pPara[3] = new CADBPROCPARA();
		pPara[3].nDataType = 12;//GString
		pPara[3].nParaType = 1;//input
		pPara[3].sName = "Ret";
		pPara[3].sInput = "";
		pPara[3].nSize = 1;
		pPara[3].sOutput = "";
		String pResultS = Client.ExecProc(pClient, strSQL, nParaCount, pPara);
		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(pResultS, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		//System.out.println("---------------prc_ChangeSwitchAccountPWD--------------");
		//System.out.println("Ret=" + pResult.getnRet() + ",sError=" + decodeErr + ",outputCount=" + pResult.getnOutputParaCount() + ",totaldataset:" + pResult.getnTotalDataSet());
		logger.info(strSQL+"调用开始 用户: {}",strUserName);
		logger.info("Ret={} ,sError={} outputCount={} totaldataset:{}", pResult.getnRet(),decodeErr, pResult.getnOutputParaCount() , pResult.getnTotalDataSet());
		List<Recordset> pOutVal = pResult.getOutputPara();
		Map<String, Object> map = new HashMap<String, Object>();
		if( pOutVal.size() > 0 )
		{
			for( int i = 0; i < pOutVal.size(); i++ )
			{
				byte [] sVal = Base64Util.decode(pOutVal.get(i).getFieldValue());
				String strVal = new String(sVal);
//				System.out.println(pOutVal.get(i).getFieldName() + ":" + strVal );
				logger.info("KEY:{} Val:{}",pOutVal.get(i).getFieldName(),strVal);
				map.put(pOutVal.get(i).getFieldName(),strVal);
			}
		}
		map.put("sError",decodeErr);
		//System.out.println("---------------prc_ChangeSwitchAccountPWD-----------");
		logger.info(strSQL+"调用结束 用户: {}",strUserName);
		return map;
	}

	public Map<String, Object> ExecProc_QueryGWUserPass_With2FA(String strUserName, String strPassWord, String strReqIP){
		int nParaCount = 7;
		CADBPROCPARA[] pPara = new CADBPROCPARA[7];
		//pPara[0] = new CADBPROCPARA();
		String strSQL= "QueryGWUserPass_With2FA";
//		String strUserName = "WTest1";
//		String strPassWord = "123456";
//		String strReqIP = "192.168.255.255";

		pPara[0] = new CADBPROCPARA();
		pPara[0].nDataType = 12;//GString
		pPara[0].nParaType = 0;//input
		pPara[0].sName = "InUserName";
		pPara[0].sInput = strUserName;
		pPara[0].nSize = pPara[0].sInput.length();
		pPara[0].sOutput = "";


		pPara[1] = new CADBPROCPARA();
		pPara[1].nDataType = 12;//GString
		pPara[1].nParaType = 0;//input
		pPara[1].sName = "InPassWord";
		pPara[1].sInput = strPassWord;
		pPara[1].nSize = pPara[1].sInput.length(); ;
		pPara[1].sOutput = "";

		pPara[2] = new CADBPROCPARA();
		pPara[2].nDataType = 12;//GString
		pPara[2].nParaType = 0;//input
		pPara[2].sName = "InRequestIP";
		pPara[2].sInput = strReqIP;
		pPara[2].nSize = pPara[2].sInput.length(); ;
		pPara[2].sOutput = "";

		pPara[3] = new CADBPROCPARA();
		pPara[3].nDataType = 12;//GString
		pPara[3].nParaType = 1;//input
		pPara[3].sName = "Result";
		pPara[3].sInput = "";
		pPara[3].nSize = 1;
		pPara[3].sOutput = "";

		pPara[4] = new CADBPROCPARA();
		pPara[4].nDataType = 12;//GString
		pPara[4].nParaType = 1;//input
		pPara[4].sName = "WhitelistIP";
		pPara[4].sInput = "";
		pPara[4].nSize = 1;
		pPara[4].sOutput = "";

		pPara[5] = new CADBPROCPARA();
		pPara[5].nDataType = 12;//GString
		pPara[5].nParaType = 1;//input
		pPara[5].sName = "FAKey";
		pPara[5].sInput = "";
		pPara[5].nSize = 1;
		pPara[5].sOutput = "";

		pPara[6] = new CADBPROCPARA();
		pPara[6].nDataType = 12;//GString
		pPara[6].nParaType = 1;//input
		pPara[6].sName = "IsEnable";
		pPara[6].sInput = "";
		pPara[6].nSize = 1;
		pPara[6].sOutput = "";
		String pResultS = Client.ExecProc(pClient, strSQL, nParaCount, pPara);
		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(pResultS, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		//System.out.println("---------------QueryGWUserPass_With2FA--------------");
		//System.out.println("Ret=" + pResult.getnRet() + ",sError=" + decodeErr + ",outputCount=" + pResult.getnOutputParaCount() + ",totaldataset:" + pResult.getnTotalDataSet());
		logger.info(strSQL+"调用开始 用户: {}",strUserName);
		logger.info("Ret={} ,sError={} outputCount={} totaldataset:{}", pResult.getnRet(),decodeErr, pResult.getnOutputParaCount() , pResult.getnTotalDataSet());
		List<Recordset> pOutVal = pResult.getOutputPara();
		Map<String, Object> map = new HashMap<String, Object>();
		if( pOutVal.size() > 0 )
		{
			for( int i = 0; i < pOutVal.size(); i++ )
			{
				byte [] sVal = Base64Util.decode(pOutVal.get(i).getFieldValue());
				String strVal = new String(sVal);
				//System.out.println(pOutVal.get(i).getFieldName() + ":" + strVal );
				logger.info("KEY:{} Val:{}",pOutVal.get(i).getFieldName(),strVal);
				map.put(pOutVal.get(i).getFieldName(),strVal);
			}
		}
		map.put("sError",decodeErr);
		logger.info(strSQL+"调用结束 用户: {}",strUserName);
		//System.out.println("---------------QueryGWUserPass_With2FA-----------");
		return map;
	}
	public void ExecProc_NoneOutputAndDataSet()
	{
		String strSQL ="JNIDemo1";
		int nParaCount = 3;
		CADBPROCPARA [] pPara = new CADBPROCPARA[3];
		pPara[0] = new CADBPROCPARA();
		pPara[0].nDataType = 12;//GString
		pPara[0].nParaType = 0;//input
		pPara[0].sName = "";
		pPara[0].sInput = new String("abcde11111");
		pPara[0].nSize = pPara[0].sInput.length();

		pPara[1] = new CADBPROCPARA();
		pPara[1].nDataType = 12;//GString
		pPara[1].nParaType = 0;//input
		pPara[1].sName = "PassWord";
		pPara[1].sInput = new String("这是测试");
		pPara[1].nSize = pPara[1].sInput.length();

		pPara[2] = new CADBPROCPARA();
		pPara[2].nDataType = 12;//GString
		pPara[2].nParaType = 0;//input
		pPara[2].sName = "IPAddr";
		pPara[2].sInput = new String("10.33.0.209");
		pPara[2].nSize = pPara[2].sInput.length();

		String pResultS = Client.ExecProc(pClient, strSQL, nParaCount, pPara);

		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(pResultS, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		System.out.println("---------------JNIDemo1--------------");
		System.out.println("Ret=" + pResult.getnRet() + ",sError=" + decodeErr + ",outputCount=" + pResult.getnOutputParaCount() + ",totaldataset:" + pResult.getnTotalDataSet());
		List<Recordset> pOutVal = pResult.getOutputPara();
		if( pOutVal.size() > 0 )
		{
			for( int i = 0; i < pOutVal.size(); i++ )
			{
				byte [] sVal = Base64Util.decode(pOutVal.get(i).getFieldValue());
				String strVal = new String(sVal);
				System.out.println(pOutVal.get(i).getFieldName() + ":" + strVal );
			}
		}
		System.out.println("---------------JNIDemo1 End-----------");
	}

	public void ExecProc()
	{
		String strSQL ="JNIDemo2";
		int nParaCount = 6;
		CADBPROCPARA [] pPara = new CADBPROCPARA[6];
		pPara[0] = new CADBPROCPARA();
		pPara[0].nDataType = 12;//GString
		pPara[0].nParaType = 0;//input
		pPara[0].sName = "";
		pPara[0].sInput = new String("这是测试");
		pPara[0].nSize = pPara[0].sInput.length();

		pPara[1] = new CADBPROCPARA();
		pPara[1].nDataType = 12;//GString
		pPara[1].nParaType = 0;//input
		pPara[1].sName = "PassWord";
		pPara[1].sInput = new String("888888");
		pPara[1].nSize = pPara[1].sInput.length();

		pPara[2] = new CADBPROCPARA();
		pPara[2].nDataType = 12;//GString
		pPara[2].nParaType = 0;//input
		pPara[2].sName = "IPAddr";
		pPara[2].sInput = new String("10.33.0.209");
		pPara[2].nSize = pPara[2].sInput.length();

		pPara[3] = new CADBPROCPARA();
		pPara[3].nDataType = 12;//GString
		pPara[3].nParaType = 1;//ounput
		pPara[3].sName = "OutUserName";
		pPara[3].sInput = "";
		pPara[3].nSize = 1;

		pPara[4] = new CADBPROCPARA();
		pPara[4].nDataType = 12;//GString
		pPara[4].nParaType = 1;//output
		pPara[4].sName = "OutPassWord";
		pPara[4].sInput = "";
		pPara[4].nSize = 1;

		pPara[5] = new CADBPROCPARA();
		pPara[5].nDataType = 12;//GString
		pPara[5].nParaType = 1;//output
		pPara[5].sName = "OutIPAddr";
		pPara[5].sInput = "";
		pPara[5].nSize = 1;

		String pResultS = Client.ExecProc(pClient, strSQL, nParaCount, pPara);

		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(pResultS, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		System.out.println("---------------JNIDemo2--------------");
		System.out.println("Ret=" + pResult.getnRet() + ",sError=" + decodeErr + ",outputCount=" + pResult.getnOutputParaCount() + ",totaldataset:" + pResult.getnTotalDataSet());
		List<Recordset> pOutVal = pResult.getOutputPara();
		if( pOutVal.size() > 0 )
		{
			for( int i = 0; i < pOutVal.size(); i++ )
			{
				byte [] sVal = Base64Util.decode(pOutVal.get(i).getFieldValue());
				String strVal = new String(sVal);
				System.out.println(pOutVal.get(i).getFieldName() + ":" + strVal );
			}
		}
		System.out.println("---------------JNIDemo2  End----------");
	}

	public void ExecProc_InAndOutAndRecordSet()
	{
		String strSQL ="JNIDemo3";
		int nParaCount = 6;
		CADBPROCPARA [] pPara = new CADBPROCPARA[6];
		pPara[0] = new CADBPROCPARA();
		pPara[0].nDataType = 12;//GString
		pPara[0].nParaType = 0;//input
		pPara[0].sName = "";
		pPara[0].sInput = new String("abcdefg");
		pPara[0].nSize = pPara[0].sInput.length();

		pPara[1] = new CADBPROCPARA();
		pPara[1].nDataType = 12;//GString
		pPara[1].nParaType = 0;//input
		pPara[1].sName = "PassWord";
		pPara[1].sInput = new String("11111111");
		pPara[1].nSize = pPara[1].sInput.length();

		pPara[2] = new CADBPROCPARA();
		pPara[2].nDataType = 12;//GString
		pPara[2].nParaType = 0;//input
		pPara[2].sName = "IPAddr";
		pPara[2].sInput = new String("192.168.0.100");
		pPara[2].nSize = pPara[2].sInput.length();

		pPara[3] = new CADBPROCPARA();
		pPara[3].nDataType = 12;//GString
		pPara[3].nParaType = 1;//ounput
		pPara[3].sName = "OutUserName";
		pPara[3].sInput = "";
		pPara[3].nSize = 1;

		pPara[4] = new CADBPROCPARA();
		pPara[4].nDataType = 12;//GString
		pPara[4].nParaType = 1;//output
		pPara[4].sName = "OutPassWord";
		pPara[4].sInput = "";
		pPara[4].nSize = 1;

		pPara[5] = new CADBPROCPARA();
		pPara[5].nDataType = 12;//GString
		pPara[5].nParaType = 1;//output
		pPara[5].sName = "OutIPAddr";
		pPara[5].sInput = "";
		pPara[5].nSize = 1;

		String pResultS = Client.ExecProc(pClient, strSQL, nParaCount, pPara);

		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(pResultS, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		System.out.println("---------------JNIDemo3--------------");
		System.out.println("Ret=" + pResult.getnRet() + ",sError=" + decodeErr + ",outputCount=" + pResult.getnOutputParaCount() + ",totaldataset:" + pResult.getnTotalDataSet());
		List<Recordset> pOutVal = pResult.getOutputPara();
		if( pOutVal.size() > 0 )
		{
			for( int i = 0; i < pOutVal.size(); i++ )
			{
				byte [] sVal = Base64Util.decode(pOutVal.get(i).getFieldValue());
				String strVal = new String(sVal);
				System.out.println(pOutVal.get(i).getFieldName() + ":" + strVal );
			}
		}
		if( pResult.getnTotalDataSet() > 0 )
		{
			pResultS = Client.GetDataSetItem(pClient);
			RecordsetSummary pSummary = parser.fromJson(pResultS, RecordsetSummary.class);
			if( pSummary.getRet() > -90)
			{
				byte [] bErr = Base64Util.decode(pSummary.getsError());
				decodeErr = new String(bErr);
			}
			else
			{
				decodeErr = pSummary.getsError();
			}
			List<RecordSetItems> pItem = pSummary.getDataSet();
			System.out.println("---------------JNIDemo3 GetSummary----------");
			System.out.println("Ret=" + pSummary.getRet() + ",sError=" + decodeErr);
			for( int j = 0; j < pItem.size(); j++ )
			{
				System.out.println("DataSet:" + pItem.get(j).getDataSetNumber() + ",Rows:" + pItem.get(j).getnRowCount() + ",ColumnCount is:" + pItem.get(j).getnColumnCount());
				GetDataSetItem(pItem.get(j).getnRowCount(),pItem.get(j).getDataSetNumber());
			}

			System.out.println("---------------JNIDemo3 GetSummary end------");
		}
		System.out.println("---------------JNIDemo3  End----------");
	}

	private List<Map<String,String>> GetDataSetItem(int nTotalRow, int nDataSetNumber)
	{
		List<Map<String,String>> list =new ArrayList<>();
		String strRet = "";
		DataTables pTable = null;
		String decodeErr = "";
		Gson parser = new Gson();

		for( int i = 0; i < nTotalRow ; i++ )
		{
			Map<String,String> map =new HashMap<>();
			//System.out.println("---------------GetDataTable In DataSet "+ nDataSetNumber +"  Row:"+ i +"-----");
			logger.info("费率数据采集 GetDataTable In DataSet {} Row: {}",nDataSetNumber,i);

			strRet = Client.GetDataFromDataSet(pClient, nDataSetNumber, i);
			pTable = parser.fromJson(strRet, DataTables.class);
			if( pTable.getRet() > -90)
			{
				byte [] bErr = Base64Util.decode(pTable.getsError());
				decodeErr = new String(bErr);
			}
			else
			{
				decodeErr = pTable.getsError();
			}
			List<Recordset> p = pTable.getDataTable();
			for( int j = 0; j < p.size(); j++ )
			{
				byte [] sVal = Base64Util.decode(p.get(j).getFieldValue());
				String strVal = new String(sVal);
				logger.info("KEY: {}, Val: {}",p.get(j).getFieldName(),strVal);
//				System.out.print(p.get(j).getFieldName() + ":" + strVal );
				map.put(p.get(j).getFieldName(),strVal);
				if( (j + 1 ) < p.size())logger.info("费率数据采集| next");
			}
			list.add(map);
		}
		return list;
	}

	public void ExecProc_InAndOutAndRecordSet_WithUnicode()
	{
		String strSQL ="JNIDemo3";
		int nParaCount = 6;
		CADBPROCPARA [] pPara = new CADBPROCPARA[6];
		pPara[0] = new CADBPROCPARA();
		pPara[0].nDataType = 12;//GString
		pPara[0].nParaType = 0;//input
		pPara[0].sName = "";
		pPara[0].sInput = new String("gbk的用户名");
		pPara[0].nSize = pPara[0].sInput.length();

		pPara[1] = new CADBPROCPARA();
		pPara[1].nDataType = 12;//GString
		pPara[1].nParaType = 0;//input
		pPara[1].sName = "PassWord";
		pPara[1].sInput = new String("这是测试");
		pPara[1].nSize = pPara[1].sInput.length();

		pPara[2] = new CADBPROCPARA();
		pPara[2].nDataType = 12;//GString
		pPara[2].nParaType = 0;//input
		pPara[2].sName = "IPAddr";
		pPara[2].sInput = new String("10.33.0.209");
		pPara[2].nSize = pPara[2].sInput.length();

		pPara[3] = new CADBPROCPARA();
		pPara[3].nDataType = 12;//GString
		pPara[3].nParaType = 1;//ounput
		pPara[3].sName = "OutUserName";
		pPara[3].sInput = "";
		pPara[3].nSize = 1;

		pPara[4] = new CADBPROCPARA();
		pPara[4].nDataType = 12;//GString
		pPara[4].nParaType = 1;//output
		pPara[4].sName = "OutPassWord";
		pPara[4].sInput = "";
		pPara[4].nSize = 1;

		pPara[5] = new CADBPROCPARA();
		pPara[5].nDataType = 12;//GString
		pPara[5].nParaType = 1;//output
		pPara[5].sName = "OutIPAddr";
		pPara[5].sInput = "";
		pPara[5].nSize = 1;

		String pResultS = Client.ExecProc(pClient, strSQL, nParaCount, pPara);

		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(pResultS, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		System.out.println("---------------ExecProc_InAndOutAndRecordSet_WithUnicode--------------");
		System.out.println("Ret=" + pResult.getnRet() + ",sError=" + decodeErr + ",outputCount=" + pResult.getnOutputParaCount() + ",totaldataset:" + pResult.getnTotalDataSet());
		List<Recordset> pOutVal = pResult.getOutputPara();
		if( pOutVal.size() > 0 )
		{
			for( int i = 0; i < pOutVal.size(); i++ )
			{
				byte [] sVal = Base64Util.decode(pOutVal.get(i).getFieldValue());
				String strVal = new String(sVal);
				System.out.println(pOutVal.get(i).getFieldName() + ":" + strVal );
			}
		}
		if( pResult.getnTotalDataSet() > 0 )
		{
			pResultS = Client.GetDataSetItem(pClient);
			RecordsetSummary pSummary = parser.fromJson(pResultS, RecordsetSummary.class);
			if( pSummary.getRet() > -90)
			{
				byte [] bErr = Base64Util.decode(pSummary.getsError());
				decodeErr = new String(bErr);
			}
			else
			{
				decodeErr = pSummary.getsError();
			}
			List<RecordSetItems> pItem = pSummary.getDataSet();
//			System.out.println("---------------ExecProc_InAndOutAndRecordSet_WithUnicode GetSummary----------");
//			System.out.println("Ret=" + pSummary.getRet() + ",sError=" + decodeErr);
			for( int j = 0; j < pItem.size(); j++ )
			{
				System.out.println("DataSet:" + pItem.get(j).getDataSetNumber() + ",Rows:" + pItem.get(j).getnRowCount() + ",ColumnCount is:" + pItem.get(j).getnColumnCount());
				GetDataSetItem(pItem.get(j).getnRowCount(),pItem.get(j).getDataSetNumber());
			}
//			System.out.println("---------------ExecProc_InAndOutAndRecordSet_WithUnicode GetSummary end------");
		}
//		System.out.println("---------------ExecProc_InAndOutAndRecordSet_WithUnicode  End----------");
	}

	public void ExecDir()
	{
		String strSQL ="select top 100 * from companyinfo";
		String strRet = Client.ExecDirectly(pClient, strSQL);
		Gson parser = new Gson();
		ProcResult pResult = parser.fromJson(strRet, ProcResult.class);

		String decodeErr = "";
		if( pResult.getnRet() > -90)
		{
			byte [] bErr = Base64Util.decode(pResult.getsError());
			decodeErr = new String(bErr);
		}
		else
		{
			decodeErr = pResult.getsError();
		}
		System.out.println("---------------ExecDir  End----------");
		System.out.println("Ret=" + pResult.getnRet() + ",sError=" + decodeErr + ",outputCount=" + pResult.getnOutputParaCount() + ",totaldataset:" + pResult.getnTotalDataSet());
		if( pResult.getnTotalDataSet() > 0 )
		{
			strRet = Client.GetDataSetItem(pClient);
			RecordsetSummary pSummary = parser.fromJson(strRet, RecordsetSummary.class);
			if( pSummary.getRet() > -90)
			{
				byte [] bErr = Base64Util.decode(pSummary.getsError());
				decodeErr = new String(bErr);
			}
			else
			{
				decodeErr = pSummary.getsError();
			}
			List<RecordSetItems> pItem = pSummary.getDataSet();
			System.out.println("---------------ExecDir GetSummary----------");
			System.out.println("Ret=" + pSummary.getRet() + ",sError=" + decodeErr);
			for( int j = 0; j < pItem.size(); j++ )
			{
				System.out.println("DataSet:" + pItem.get(j).getDataSetNumber() + ",Rows:" + pItem.get(j).getnRowCount() + ",ColumnCount is:" + pItem.get(j).getnColumnCount());
				GetDataSetItem(pItem.get(j).getnRowCount(),pItem.get(j).getDataSetNumber());
			}
			System.out.println("---------------ExecProc_InAndOutAndRecordSet_WithUnicode GetSummary end------");
		}
		System.out.println("---------------ExecDir  End----------");
	}



	public int GetConnectStatus()
	{
		return Client.GetConnectStatus(pClient);
	}
}
