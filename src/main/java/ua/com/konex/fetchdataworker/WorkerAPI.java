package ua.com.konex.fetchdataworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ua.com.konex.fetchdataworker.telegram.TelegramBot;

import javax.annotation.PostConstruct;
import java.io.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class WorkerAPI {
    private static final Logger log = LogManager.getLogger(WorkerAPI.class.getName());
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
    String[] forbiddenInstruction = {"DROP", "ALTER", "ROLE", "TRIGGER", "DATABASE", "SCHEMA", "TABLESPACE", "RESTORE POINT"};

    @Value("${telegram.chatIds}")
    String telegramChatIds;

    @Autowired
    TelegramBot telegramBot;

    @PostConstruct
    void mainMethod() {
        ArrayList<Map<String, Object>> workers = null;
        try {
            workers = getDataObjectsFromFile(System.getProperty("user.dir") + File.separator + "config.json");
        } catch (Exception e) {
            JSONObject invalidObject = new JSONObject();
            invalidObject.put("errorMessage", e.getMessage());
            invalidObject.put("error", "problem with config.json");
            telegramBot.sendMessage(telegramChatIds, invalidObject.toString(4));
        }
        processWorkers(workers);
    }

    private void processWorkers(ArrayList<Map<String, Object>> workers) {
        for (Map<String, Object> element : workers) {
            if (!(Boolean) element.getOrDefault("status", false)) continue;
            if (element.getOrDefault("jsonName", "").equals("")) continue;
            Map<String, Object> databaseData = (Map<String, Object>) element.get("database");
            JSONArray dbData = connectionDBResponse(
                    databaseData.get("url").toString(),
                    databaseData.get("user").toString(),
                    databaseData.get("password").toString(),
                    databaseData.get("sqlQuery").toString(),
                    element.get("author").toString());
            if (dbData.isEmpty()) continue;
            JSONObject result = new JSONObject();
            result.put("author", element.get("author").toString());
            result.put("jsonName", element.get("jsonName").toString());
            result.put("payload", dbData);
            result.put("date", new SimpleDateFormat("yyyy.MM.dd HH:mm:ss").format(new Date()));
            Map<String, Object> rabbit = (Map<String, Object>) element.get("rabbit");
            scheduleTask(rabbit, result, (Boolean) element.getOrDefault("logFile", false));
        }
    }

    private ArrayList<Map<String, Object>> getDataObjectsFromFile(String fileName) throws IOException {
        InputStream inputStream = new FileInputStream(new File(fileName));
        StringBuilder resultStringBuilder = new StringBuilder();
        try (BufferedReader br
                     = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                resultStringBuilder.append(line).append("\n");
            }
        }
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(resultStringBuilder.toString(), ArrayList.class);
    }

    public JSONArray connectionDBResponse(String url, String user, String password, String sqlQuery, String author) {
        if (Arrays.stream(forbiddenInstruction).anyMatch(sqlQuery::contains)) {
            String[] forbiddenWords = Arrays.stream(forbiddenInstruction).filter(sqlQuery.toUpperCase()::contains).collect(Collectors.toSet()).toArray(new String[0]);
            JSONObject invalidObject = new JSONObject();
            invalidObject.put("author", author);
            invalidObject.put("forbiddenWords", Arrays.toString(forbiddenWords));
            telegramBot.sendMessage(telegramChatIds, invalidObject.toString(4));
        }

        JSONArray result = new JSONArray();
        try {
            Connection connection = DriverManager.getConnection(url, user, password);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sqlQuery);
            //start create json
            ResultSetMetaData md = resultSet.getMetaData();
            int numCols = md.getColumnCount();
            List<String> colNames = IntStream.range(0, numCols)
                    .mapToObj(i -> {
                        try {
                            return md.getColumnName(i + 1);
                        } catch (SQLException e) {
                            e.printStackTrace();
                            return "?";
                        }
                    })
                    .collect(Collectors.toList());
            while (resultSet.next()) {
                JSONObject row = new JSONObject();
                colNames.forEach(cn -> {
                    try {
                        row.put(cn, resultSet.getObject(cn));
                    } catch (JSONException | SQLException e) {
                        e.printStackTrace();
                    }
                });
                result.put(row);
            }
            connection.close();
            // end create json
        } catch (SQLException e) {
            JSONObject invalidObject = new JSONObject();
            invalidObject.put("errorMessage", e.getMessage());
            invalidObject.put("url", url);
            invalidObject.put("user", user);
            invalidObject.put("password", password);
            invalidObject.put("sqlQuery", sqlQuery);
            invalidObject.put("author", author);
            telegramBot.sendMessage(telegramChatIds, invalidObject.toString(4));
        }
        return result;
    }


    private void scheduleTask(Map<String, Object> rabbit, JSONObject result, Boolean logFile) {

        String host = rabbit.getOrDefault("host", "").toString();
        String userName = rabbit.getOrDefault("userName", "").toString();
        String password = rabbit.getOrDefault("password", "").toString();
        String exchange = rabbit.getOrDefault("exchange", "").toString();
        String routingKey = rabbit.getOrDefault("routingKey", "").toString();

        if (host.equals("") || userName.equals("") || password.equals("")) {
            JSONObject invalidObject = new JSONObject();
            invalidObject.put("errorMessage", "RabbitMQ exception");
            invalidObject.put("host", host);
            invalidObject.put("userName", userName);
            invalidObject.put("password", password);
            telegramBot.sendMessage(telegramChatIds, invalidObject.toString(4));
            return;
        }

        AtomicInteger counterLog = new AtomicInteger(0);
        AtomicBoolean messageAlreadySend = new AtomicBoolean(true);
        AtomicBoolean logFileStatus = new AtomicBoolean(logFile);

        Runnable task = () -> {
            try {
                taskExecutor(host, userName, password, exchange, routingKey, result);
                if (logFileStatus.get()) {
                    if (counterLog.get() % 10 == 0) {
                        log.info("\nsuccess send: " + result.get("jsonName"));
                        counterLog.set(0);
                    }
                }
                counterLog.addAndGet(1);
                messageAlreadySend.set(true);
            } catch (IOException e) {
                JSONObject invalidObject = new JSONObject();
                invalidObject.put("error", e.getMessage());
                invalidObject.put("host", host);
                invalidObject.put("userName", userName);
                invalidObject.put("password", password);
                invalidObject.put("routingKey", routingKey);
                invalidObject.put("exchange", exchange);
                if (messageAlreadySend.get()){
                    telegramBot.sendMessage(telegramChatIds, invalidObject.toString(4));
                    messageAlreadySend.set(false);
                }

            } catch (TimeoutException e) {
                JSONObject invalidObject = new JSONObject();
                invalidObject.put("error", e.getMessage());
                invalidObject.put("host", host);
                invalidObject.put("userName", userName);
                invalidObject.put("password", password);
                invalidObject.put("exchange", exchange);
                invalidObject.put("routingKey", routingKey);
                if (messageAlreadySend.get()){
                    telegramBot.sendMessage(telegramChatIds, invalidObject.toString(4));
                    messageAlreadySend.set(false);
                }
            }
        };
        executor.scheduleAtFixedRate(task, 2, Integer.parseInt(rabbit.get("timeoutSeconds").toString()), TimeUnit.SECONDS);
    }

    void taskExecutor(String host, String userName, String password, String exchange, String routingKey, JSONObject data) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setUsername(userName);
        factory.setPassword(password);

        com.rabbitmq.client.Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();
        channel.basicPublish(exchange, routingKey, null, data.toString().getBytes());

        channel.close();
        connection.close();
    }

    public void restartService(){
        executor.shutdown();
        executor = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
        mainMethod();
    }
}
