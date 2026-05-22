package com.tianji.aigc.controller;

import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatDTO;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.annotations.NoWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.N;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @NoWrapper // 标记结果不进行包装
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEventVO> chat(@RequestBody ChatDTO chatDTO) {
        return this.chatService.chat(chatDTO.getQuestion(), chatDTO.getSessionId());
    }

    @NoWrapper
    @GetMapping(value = "/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestParam("input") String input) {
        // 使用 stream().content() 返回 Flux 流式数据
        return this.chatService.streamChat(input);
    }
}
