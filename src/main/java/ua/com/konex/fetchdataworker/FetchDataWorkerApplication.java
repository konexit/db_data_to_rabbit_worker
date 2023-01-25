package ua.com.konex.fetchdataworker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FetchDataWorkerApplication {

	private static final Logger log = LogManager.getLogger(FetchDataWorkerApplication.class.getName());

	public static void main(String[] args) {
		SpringApplication.run(FetchDataWorkerApplication.class, args);
		log.info("\n======================================= APPLICATION STARTED =======================================");
	}
}
