package CacheDB;

import java.util.List;

public class CacheDBResult {
    static public class CADBPROCPARA
    {
    	String sName;//参数名字
    	int nParaType;//参数类型:0-输入,1-输出
    	int nDataType;//数据类型:目前固定14(字符串)
    	int nSize;//若为输入参数,则取该参数长度;若为输出参数,则为1
    	String sInput;//输入参数
    	String sOutput;//输出参数,留空
    	int nRetSize; //Reserved
    }

    static public class Recordset
    {
    	private String FieldName;
    	private String FieldValue;
    	public String getFieldName()
    	{
    		return FieldName;
    	}
    	public void setFieldName(String s)
    	{
    		this.FieldName = s;
    	}
    	public String getFieldValue()
    	{
    		return this.FieldValue;
    	}
    	public void setFieldValue(String s)
    	{
    		FieldValue = s;
    	}
    }

    static public class ProcResult
    {
    	private int nRet;
    	private int OutputParaCount;
    	private int TotalDataSet;
    	private String sError;
    	private List<Recordset> OutputPara;
    	public int getnRet()
    	{
    		return nRet;
    	}
    	public void setnRet(int nR)
    	{
    		this.nRet = nR;
    	}

    	public int getnOutputParaCount()
    	{
    		return OutputParaCount;
    	}
    	public void setnOutputParaCount(int nC)
    	{
    		this.OutputParaCount = nC;
    	}

    	public int getnTotalDataSet()
    	{
    		return TotalDataSet;
    	}
    	public void setnTotalDataSet(int nS)
    	{
    		this.TotalDataSet = nS;
    	}

    	public String getsError()
    	{
    		return sError;
    	}
    	public void setsError(String s)
    	{
    		this.sError = s;
    	}

    	public List<Recordset> getOutputPara()
    	{
    		return OutputPara;
    	}
    	public void setOutputPara(List<Recordset> s)
    	{
    		this.OutputPara = s;
    	}
    }

    static public class RecordSetItems
    {
    	private int nRowCount;
    	private int nColumnCount;
    	private int DataSetNumber;
    	public int getnRowCount()
    	{
    		return nRowCount;
    	}
    	public void setnRowCount(int n)
    	{
    		this.nRowCount = n;
    	}
    	public int getnColumnCount()
    	{
    		return nColumnCount;
    	}
    	public void setnColumnCount(int n)
    	{
    		this.nColumnCount = n;
    	}
    	public int getDataSetNumber()
    	{
    		return DataSetNumber;
    	}
    	public void setDataSetNumber(int n)
    	{
    		this.DataSetNumber = n;
    	}
    }

    static public class RecordsetSummary
    {
    	private int Ret;
    	private String sError;
    	private List<RecordSetItems> DataSet;
    	public int getRet()
    	{
    		return Ret;
    	}
    	public void setRet(int n)
    	{
    		this.Ret = n;
    	}
    	public String getsError()
    	{
    		return sError;
    	}
    	public void setsError(String s)
    	{
    		this.sError = s;
    	}
    	public List<RecordSetItems> getDataSet()
    	{
    		return DataSet;
    	}
    	public void setDataSet(List<RecordSetItems> s)
    	{
    		this.DataSet = s;
    	}
    }

    static public class DataTables
    {
    	private int Ret;
    	private String sError;
    	private List<Recordset> DataTable;
    	public int getRet()
    	{
    		return Ret;
    	}
    	public void setRet(int n)
    	{
    		this.Ret = n;
    	}
    	public String getsError()
    	{
    		return sError;
    	}
    	public void setsError(String s)
    	{
    		this.sError = s;
    	}
    	public List<Recordset> getDataTable()
    	{
    		return DataTable;
    	}
    	public void setDataTable(List<Recordset> s)
    	{
    		this.DataTable = s;
    	}
    }

}
