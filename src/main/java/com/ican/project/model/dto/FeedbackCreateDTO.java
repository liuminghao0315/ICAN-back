package com.ican.project.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FeedbackCreateDTO {

    @NotBlank(message = "关联任务ID不能为空")
    private String taskId;

    @NotBlank(message = "关联视频ID不能为空")
    private String videoId;

    @NotBlank(message = "反馈类型不能为空")
    private String feedbackType;

    private String module;

    @NotBlank(message = "反馈内容不能为空")
    @Size(min = 1, message = "反馈内容不能为空")
    private String content;
}
