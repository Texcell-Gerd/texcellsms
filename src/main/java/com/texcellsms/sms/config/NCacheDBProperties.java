package com.texcellsms.sms.config;

import CacheDB.NCacheDBLink;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Data
@Component
@ConfigurationProperties(prefix = "ncache-db")
public class NCacheDBProperties {

    private String server = "192.168.125.250";

    private int port = 11781;

    private String userName = "example";

    private String password = "example";

    private String db = "example";

    private String pageCoding = "utf-8";

    private String dbCoding = "utf-8";

    private Connection rateQuery = new Connection();

    @PostConstruct
    public void applyToNCacheDBLink() {
        NCacheDBLink.configure(server, port, userName, password, db, pageCoding, dbCoding);
    }

    @Data
    public static class Connection {

        private String server = "192.168.125.250";

        private int port = 18801;

        private String userName = "auth";

        private String password = "auth";

        private String db = "auth";

        private String pageCoding = "utf-8";

        private String dbCoding = "utf-8";
    }
}
