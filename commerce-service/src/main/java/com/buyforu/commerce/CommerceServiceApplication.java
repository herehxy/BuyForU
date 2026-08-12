package com.buyforu.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Commerce 服务启动入口；开启调度以运行预占过期回收和 Outbox 投递。 */
@SpringBootApplication
@EnableScheduling
public class CommerceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommerceServiceApplication.class, args);
    }

}
