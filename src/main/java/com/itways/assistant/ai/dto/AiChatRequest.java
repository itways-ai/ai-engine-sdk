package com.itways.assistant.ai.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatRequest extends BaseAiRequest {
	private String model;
	private List<AiMessage> messages;
	private Double temperature;
	private Integer maxTokens;
	private List<AiWrappedFile> files;
}
