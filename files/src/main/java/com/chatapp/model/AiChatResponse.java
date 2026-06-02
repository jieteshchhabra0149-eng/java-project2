package com.chatapp.model;

public class AiChatResponse {

    private boolean success;
    private String reply;
    private String error;

    public static AiChatResponse ok(String reply) {
        AiChatResponse r = new AiChatResponse();
        r.success = true;
        r.reply = reply;
        return r;
    }

    public static AiChatResponse fail(String error) {
        AiChatResponse r = new AiChatResponse();
        r.success = false;
        r.error = error;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
