package cn.videohub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VideoHubApplication {
   public static void main(String[] args) {
      SpringApplication.run(cn.videohub.VideoHubApplication.class, args);
   }
}
