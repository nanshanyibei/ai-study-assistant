package com.nanshan.study.llm;

/**
 * 流式输出回调。DeepSeek-V4 / R1 这类推理模型会先吐「思考过程」再吐正文，
 * 两条通道要分开，否则前端会把思考内容和答案混在一起。
 */
public interface StreamSink {

    /** 正文增量 */
    void content(String text);

    /** 思考过程增量，默认可忽略 */
    default void reasoning(String text) {
    }
}
