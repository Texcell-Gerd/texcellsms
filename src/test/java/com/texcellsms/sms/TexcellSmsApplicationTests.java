package com.texcellsms.sms;


import com.texcellsms.sms.CacheDB.NCacheDBLink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TexcellSmsApplicationTests {

    public static NCacheDBLink pLink = null;
    @Test
    void contextLoads() {
        System.out.println("test submit xunxun");
        pLink = new NCacheDBLink();
        pLink.InitEnv("192.168.112.250",11781,"example","example","example","utf-8","utf-8");
        pLink.ConnectToCacheDB();
//		pLink.ExecProc_NoneOutputAndDataSet();
//		pLink.ExecProc();
//		pLink.ExecProc_InAndOutAndRecordSet();
//		pLink.ExecDir();
//		pLink.ExecProc_InAndOutAndRecordSet_WithUnicode();
        pLink.ExecProc_QueryGWUserPass_With2FA();
        pLink.Disconnect();

    }

}
