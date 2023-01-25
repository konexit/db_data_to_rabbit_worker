package ua.com.konex.fetchdataworker.telegram;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ua.com.konex.fetchdataworker.WorkerAPI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@Component
public class TelegramBot extends TelegramLongPollingBot {

    WorkerAPI workerAPI;

    @Value("${telegram.chatIds}")
    String telegramChatIds;

    @Autowired
    public TelegramBot(@Lazy WorkerAPI workerAPI) {
        this.workerAPI = workerAPI;
    }

    @Autowired
    BotConfig config;

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            String chatId = Long.toString(update.getMessage().getChatId());
            switch (messageText) {
                case "//start":
                    startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
                    break;
                case "//reboot":
                    workerAPI.restartService();
                    break;
                default:
                    sendMessage(telegramChatIds, "Sorry command was not recognized");
            }
        }
    }

    private void startCommandReceived(String chatId, String name) {
        String answer = "Hi , " + name;
        sendMessage(chatId, answer);
    }

    public void sendMessage(String chatIds, String textToSend) {
        SendMessage message = new SendMessage();
        List<String> chatIdsArray =  Arrays.asList(chatIds.split(","));
        for(String chatId : chatIdsArray) {
            message.setChatId(String.valueOf(chatId));
            message.setText(textToSend);
            try {
                execute(message);
            } catch (TelegramApiException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
