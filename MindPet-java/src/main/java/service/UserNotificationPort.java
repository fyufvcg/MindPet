package service;

/**
 * Delivers an asynchronous message to a user without coupling domain services
 * to a particular chat platform.
 */
public interface UserNotificationPort {

    void sendText(String userId, String title, String body);
}
