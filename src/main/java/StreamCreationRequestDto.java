
public class StreamCreationRequestDto {
    private String streamName;
    private String rtspUrl;
    private String description;

    // Constructors, Getters, Setters if not using Lombok
    public StreamCreationRequestDto() {}

    public StreamCreationRequestDto(String streamName, String rtspUrl, String description) {
        this.streamName = streamName;
        this.rtspUrl = rtspUrl;
        this.description = description;
    }

    public String getStreamName() { return streamName; }
    public void setStreamName(String streamName) { this.streamName = streamName; }
    public String getRtspUrl() { return rtspUrl; }
    public void setRtspUrl(String rtspUrl) { this.rtspUrl = rtspUrl; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}