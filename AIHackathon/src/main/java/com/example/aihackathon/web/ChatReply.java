package com.example.aihackathon.web;

/** Body trả về của POST /api/chat. */
public record ChatReply(String conversationId, String reply) {
}
