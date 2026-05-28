package com.tianji.aigc.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;


@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai", name = "chat-type", havingValue = "ENHANCE")
public class ChatServiceImpl implements ChatService {

    // 输出结束的标记
    private static final ChatEventVO STOP_EVENT = ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();
    private final ChatClient chatClient;
    private final ChatClient openAiChatClient;
    private final SystemPromptConfig systemPromptConfig;
    private final ChatMemory chatMemory;
    private final StringRedisTemplate stringRedisTemplate;
    private final VectorStore vectorStore;
    private final ChatSessionService chatSessionService;
    // 存储大模型的生成状态，这里采用ConcurrentHashMap是确保线程安全
    // 目前的版本暂时用Map实现，如果考虑分布式环境的话，可以考虑用redis来实现
//    private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();
    private static final String GENERATE_STATUS_KEY = "GENERATE_STATUS";

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // 将会话id转化为对话id
        var conversationId = ChatService.getConversationId(sessionId);
        // 大模型输出内容的缓存器，用于在输出中断后的数据存储
        var outputBuilder = new StringBuilder();
        var hashOps = this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY);
        // 生成请求id
        var requestId = IdUtil.fastSimpleUUID();
        // 获取用户id
        var userId = UserContext.getUser();

        // 异步更新会话信息
        this.chatSessionService.update(sessionId, question, userId);

        // 创建RAG增强
        var qaAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.6d) // 相似度阈值
                        .topK(6) // 搜索的条数
                        .build())
                .build();

        return this.chatClient.prompt()
                .system(promptSystem -> promptSystem
                        .text(this.systemPromptConfig.getChatSystemMessage().get()) // 设置系统提示语
                        .param("now", DateUtil.now()) // 设置当前时间的参数
                )
                .advisors(advisor -> advisor
                        // 设置RAG增强
                        .advisors(qaAdvisor)
                        .param(ChatMemory.CONVERSATION_ID, conversationId)) //设置对话记忆中的对话id
                .toolContext(Map.of(Constant.REQUEST_ID, requestId, Constant.USER_ID, userId)) //通过工具上下文传递参数
                .user(question)
                .stream()
                .chatResponse()
                .doFirst(() -> hashOps.put(sessionId, "true")) // 第一次输出内容时执行
                .doOnComplete(() -> hashOps.delete(sessionId)) // 完成时执行，删除标识
                .doOnError(throwable -> hashOps.delete(sessionId)) // 出现异常时，删除标识
                .doOnCancel(() -> {
                    // 当输出被取消时，保存输出的内容到历史记录中
                    this.saveStopHistoryRecord(conversationId, outputBuilder.toString());
                })
                // 输出过程中，判断是否正在输出，如果正在输出，则继续输出，否则结束输出
                .takeWhile(response -> { // 通过返回值来控制Flux流是否继续，true：继续，false：终止
                    return hashOps.get(sessionId) != null;
                })
                .map(chatResponse -> {
                    // 对于响应结果进行处理，如果是最后一条数据，就把此次消息id放到内存中
                    // 主要用于存储消息数据到 redis中，可以根据消息di获取的请求id，再通过请求id就可以获取到参数列表了
                    // 从而解决，在历史聊天记录中没有外参数的问题
                    var finishReason = chatResponse.getResult().getMetadata().getFinishReason();
                    if (StrUtil.equals(Constant.STOP, finishReason)) {
                        // 获取到消息id
                        var messageId = chatResponse.getMetadata().getId();
                        // 将消息id与请求id进行关联
                        ToolResultHolder.put(messageId, Constant.REQUEST_ID, requestId);
                    }

                    // 获取大模型的输出的内容
                    String text = chatResponse.getResult().getOutput().getText();
                    // 追加到输出内容中
                    outputBuilder.append(text);
                    // 封装响应对象
                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .concatWith(Flux.defer(() -> {
                    // 通过请求id获取到参数列表，如果不为空，就将其追加到返回结果中
                    var map = ToolResultHolder.get(requestId);
                    if (CollUtil.isNotEmpty(map)) {
                        ToolResultHolder.remove(requestId);
                        // 工具被调用了，需要向前端传递参数
                        return Flux.just(ChatEventVO.builder()
                                .eventType(ChatEventTypeEnum.PARAM.getValue())
                                .eventData(map)
                                .build(), STOP_EVENT);
                    }
                    return Flux.just(STOP_EVENT); // 结束标识
                }));
//                .concatWith(Flux.just(ChatEventVO.builder()  // 标记输出结束
//                        .eventType(ChatEventTypeEnum.STOP.getValue())
//                        .build()));
    }

    /**
     * 保存停止输出的记录
     *
     * @param conversationId 会话id
     * @param content        大模型输出的内容
     */
    private final ChatMemoryRepository chatMemoryRepository;
    private void saveStopHistoryRecord(String conversationId, String content) {

        /*
            default void add(String conversationId, Message message) {
                Assert.hasText(conversationId, "conversationId cannot be null or empty");
                Assert.notNull(message, "message cannot be null");
                this.add(conversationId, List.of(message));
            }

            void add(String conversationId, List<Message> messages);
         */

        /*
           public void add(String conversationId, List<Message> messages) {
               Assert.hasText(conversationId, "conversationId cannot be null or empty");
               Assert.notNull(messages, "messages cannot be null");
               Assert.noNullElements(messages, "messages cannot contain null elements");
               List<Message> memoryMessages = this.chatMemoryRepository.findByConversationId(conversationId);
               List<Message> processedMessages = this.process(memoryMessages, messages);
               this.chatMemoryRepository.saveAll(conversationId, processedMessages);
           }
         */
        this.chatMemory.add(conversationId, new AssistantMessage(content));
//        this.chatMemoryRepository.saveAll(conversationId, List.of(new AssistantMessage(content)));
    }

    @Override
    public Flux<String> streamChat(String input) {
        return this.chatClient.prompt()
                .user(input)
                .stream()
                .content();
    }

    @Override
    public void stop(String sessionId) {
        var hashOps = this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY);
        // 移除标记
//        GENERATE_STATUS.remove(sessionId);
        hashOps.delete(sessionId);
    }

    @Override
    public String chatText(String question) {
//        return this.chatClient.prompt()
//                .system(promptSystem -> promptSystem.text(this.systemPromptConfig.getTextSystemMessage().get()))
//                .user(question)
//                .call()
//                .content();
        return "测试回复";
    }
}
