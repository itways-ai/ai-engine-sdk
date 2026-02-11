package com.itways.assistant.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWrappedFile {
    private byte[] content;
    private String filename;
    private String mimeType;
}
