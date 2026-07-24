package com.chattera.chat.service;

import java.util.List;

import com.chattera.chat.domain.Message;

public record MessagePage(List<Message> messages, String nextCursor) {
}
