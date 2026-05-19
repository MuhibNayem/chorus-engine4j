package com.chorus.engine.core.multimodal;

import java.util.Base64;

/**
 * Represents multimodal media content attached to a message.
 * Supports image, audio, and video content via base64 data URIs or URLs.
 */
public sealed interface MediaContent {

    String mimeType();

    /**
     * Image content with base64-encoded data or URL.
     */
    record ImageContent(String mimeType, String data, ImageSource source) implements MediaContent {
        public enum ImageSource { BASE64, URL }

        public static ImageContent fromBase64(String mimeType, byte[] bytes) {
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return new ImageContent(mimeType, b64, ImageSource.BASE64);
        }

        public static ImageContent fromUrl(String url) {
            return new ImageContent("image/*", url, ImageSource.URL);
        }
    }

    /**
     * Audio content with base64-encoded data or URL.
     */
    record AudioContent(String mimeType, String data, AudioSource source) implements MediaContent {
        public enum AudioSource { BASE64, URL }

        public static AudioContent fromBase64(String mimeType, byte[] bytes) {
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return new AudioContent(mimeType, b64, AudioSource.BASE64);
        }

        public static AudioContent fromUrl(String url) {
            return new AudioContent("audio/*", url, AudioSource.URL);
        }
    }

    /**
     * Video content with base64-encoded data or URL.
     */
    record VideoContent(String mimeType, String data, VideoSource source) implements MediaContent {
        public enum VideoSource { BASE64, URL }

        public static VideoContent fromBase64(String mimeType, byte[] bytes) {
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return new VideoContent(mimeType, b64, VideoSource.BASE64);
        }

        public static VideoContent fromUrl(String url) {
            return new VideoContent("video/*", url, VideoSource.URL);
        }
    }

    /**
     * Plain text content (for mixed multimodal messages).
     */
    record TextContent(String text) implements MediaContent {
        @Override
        public String mimeType() { return "text/plain"; }
    }
}
