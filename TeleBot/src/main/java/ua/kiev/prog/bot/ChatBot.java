package ua.kiev.prog.bot;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ua.kiev.prog.model.User;
import ua.kiev.prog.service.UserService;

import java.io.InputStream;
import java.util.List;

@Component
@PropertySource("classpath:telegram.properties")
public class ChatBot extends TelegramLongPollingBot {

    private static final Logger LOGGER = LogManager.getLogger(ChatBot.class);

    private static final String BROADCAST = "broadcast ";
    private static final String LIST_USERS = "users";
    private static final String DELETE_USER = "delete_user";
    private static final String SET_ADMIN = "set_admin ";

    @Value("${bot.name}")
    private String botName;

    @Value("${bot.token}")
    private String botToken;

    private final UserService userService;

    public ChatBot(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText())
            return;

        final String text = update.getMessage().getText();
        final Long chatId = update.getMessage().getChatId();

        User user = userService.findByChatId(chatId);

        if (checkIfAdminCommand(user, text)) return;
        if (checkDeleteCommand(user, text)) return;

        BotContext context;
        BotState state;

        if (user == null) {
            state = BotState.getInitialState();
            sendPhoto(chatId,"images.jpg");

            user = new User(chatId, state.ordinal());
            userService.addUser(user);

            context = BotContext.of(this, user, text);
            state.enter(context);

            LOGGER.info("New user registered: " + chatId);
        } else {
            context = BotContext.of(this, user, text);
            state = BotState.byId(user.getStateId());

            LOGGER.info("Update received for user in state: " + state);
        }

        state.handleInput(context);

        do {
            state = state.nextState();
            state.enter(context);
        } while (!state.isInputNeeded());

        user.setStateId(state.ordinal());
        userService.updateUser(user);
    }

    private boolean checkDeleteCommand(User user, String text) {
        if (text.equals(DELETE_USER)) {
            userService.deleteUser(user);
            LOGGER.info("Delete user: " + text);
            return true;
        }
        LOGGER.info("No delete user: " + text);
        return false;
    }

    private boolean checkIfAdminCommand(User user, String text) {
        if (user == null || !user.getAdmin())
            return false;

        if (text.startsWith(BROADCAST)) {
            LOGGER.info("Admin command received: " + BROADCAST);

            text = text.substring(BROADCAST.length());
            broadcast(text);

            return true;
        } else if (text.equals(LIST_USERS)) {
            LOGGER.info("Admin command received: " + LIST_USERS);

            listUsers(user);
            return true;
        } else if (text.startsWith(SET_ADMIN)) {
             text = text.substring(SET_ADMIN.length());
             User newAdmin = userService.findByPhone(text);
             if (newAdmin != null) {
                 newAdmin.setAdmin(true);
             }
             userService.updateUser(newAdmin);

             LOGGER.info("Admin command received: " + SET_ADMIN);
             return true;
        }

        return false;
    }

    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(Long.toString(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendPhoto(Long chatId, String photoName) {
        InputStream is = getClass().getClassLoader()
                .getResourceAsStream(photoName);

        SendPhoto message = new SendPhoto();
        message.setChatId(Long.toString(chatId));
        message.setPhoto(new InputFile(is, "test"));
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void listUsers(User admin) {
        StringBuilder sb = new StringBuilder("All users list:\r\n");
        List<User> users = userService.findAllUsers();

        users.forEach(user ->
            sb.append(user.getId())
                    .append(' ')
                    .append(user.getPhone())
                    .append(' ')
                    .append(user.getEmail())
                    .append("\r\n")
        );

        sendPhoto(admin.getChatId(),"test.png");
        sendMessage(admin.getChatId(), sb.toString());
    }

    private void broadcast(String text) {
        List<User> users = userService.findAllUsers();
        users.forEach(user -> sendMessage(user.getChatId(), text));
    }
}
