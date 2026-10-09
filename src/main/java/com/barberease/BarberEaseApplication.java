package com.barberease;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;

@SpringBootApplication
@EnableScheduling
public class BarberEaseApplication {
    public static void main(String[] args) {
        SpringApplication.run(BarberEaseApplication.class, args);
    }

    @EventListener({ApplicationReadyEvent.class})
    public void applicationReadyEvent() {
        System.out.println("Application started... launching Edge now");
        String url = "http://localhost:6080";
        try {
            Runtime.getRuntime().exec("cmd /c start msedge " + url);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
