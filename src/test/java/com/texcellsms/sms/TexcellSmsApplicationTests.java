package com.texcellsms.sms;


import CacheDB.NCacheDBLink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TexcellSmsApplicationTests {

    public static NCacheDBLink pLink = null;
    @Test
    void contextLoads() {
        System.out.println("test submit xunxun");
        pLink = NCacheDBLink.getNCacheDBLink();
        pLink.InitEnv("192.168.112.250",11781,"example","example","example","utf-8","utf-8");
        pLink.ConnectToCacheDB();
//		pLink.ExecProc_NoneOutputAndDataSet();
//		pLink.ExecProc();
//		pLink.ExecProc_InAndOutAndRecordSet();
//		pLink.ExecDir();
//		pLink.ExecProc_InAndOutAndRecordSet_WithUnicode();
//        pLink.ExecProc_QueryGWUserPass_With2FA("WTest1","123456","192.168.255.255");
        pLink.web_QueryUserRate("WTest1");
        System.out.println("Status"+pLink.GetConnectStatus());
//        pLink.Disconnect();
    }

}
