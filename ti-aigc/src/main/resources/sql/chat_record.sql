CREATE TABLE `chat_record` (
                               `id` bigint NOT NULL COMMENT '数据id',
                               `conversation_id` varchar(100) COLLATE utf8mb4_bin NOT NULL COMMENT '对话id',
                               `data` TEXT DEFAULT NULL COMMENT '对话数据' COLLATE 'utf8mb4_bin',
                               `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               `creater` bigint NOT NULL COMMENT '创建人',
                               `updater` bigint NOT NULL COMMENT '更新人',
                               PRIMARY KEY (`id`) USING BTREE,
                               KEY `conversation_id_index` (`conversation_id`) USING BTREE,
                               KEY `create_time_index` (`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='对话record'