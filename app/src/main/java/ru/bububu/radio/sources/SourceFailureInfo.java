package ru.bububu.radio.sources;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Safe UI projection. It deliberately retains no throwable, URL, response body or headers. */
public final class SourceFailureInfo {
    public enum Kind { HTTP, COOLDOWN, EXCESSIVE, STORAGE, TIMEOUT, CANCELLED, NETWORK, INVALID, UNAVAILABLE }
    public final Kind kind;
    public final int status;
    public final RetryAfterParser.Kind retryAfterKind;
    public final long retryAtMonotonicMillis;
    public final String reason;
    private static final String DOWNLOAD_PREFIX="DL1|";
    private static final Set<String> DOWNLOAD_REASONS=new HashSet<>(Arrays.asList((
            "ARTICLE_REDIRECT_UNSUPPORTED ATOM_FEED_METADATA BUSY CANCELLED CHARSET_CONFLICT CHARSET_ERROR CONFLICTING_BASE CONTENT_ENCODING "+
            "COOLDOWN_STORAGE COOLDOWN_UNREPRESENTABLE DISCOVERY_LIMIT DNS_RESPONSE DOM_LIMIT EMPTY_HTTP_BODY FEED_TYPE_CHANGED HEADER_LIMIT HEADER_SYNTAX "+
            "HEADLINE_ONLY_NO_TEXT HOST_COOLDOWN HOST_LIMIT HTML_BYTES_LIMIT HTML_TOKEN_LIMIT HTML_PAGE_EMPTY HTML_PAGE_UNSUPPORTED HTML_PAGE_ACCESS_REQUIRED HTML_INDEX_UNREADABLE HTML_INDEX_ARTICLE_IDENTITY HTTPS_REQUIRED INVALID_OR_LIMIT ITEM_LIMIT ITEM_NOT_IN_CURRENT_WINDOW "+
            "MIME_MISMATCH NETWORK_ERROR NON_PUBLIC_ADDRESS NO_FRESH_FEED NO_OWN_TEXT NO_READABLE_TEXT NO_SUPPORTED_FEED PEER_MISMATCH REDIRECT_LIMIT REDIRECT_LOCATION REDIRECT_LOOP "+
            "RSS_CHANNEL SAVED_TEXT_NOT_AVAILABLE SOURCE_DISABLED_NO_SAVED_TEXT SOURCE_NAME_LIMIT SOURCE_UNAVAILABLE STALE_ITEM_RETRY STORAGE_OR_LIMIT "+
            "TELEGRAM_CHANNEL_MISMATCH TELEGRAM_CONFLICTING_POST TELEGRAM_INPUT TELEGRAM_MULTIPLE_OWN_BODY TELEGRAM_PREVIEW_STRUCTURE TEXT_LIMIT TIMEOUT TITLE_LIMIT "+
            "TRANSPORT_CLOSED UNEXPECTED_304 UNSUPPORTED_FEED_FORMAT UNSUPPORTED_TEXT_CONSTRUCT UNSUPPORTED_XHTML UNVETTED_PEER URL_CREDENTIALS URL_HOST URL_LIMIT URL_SYNTAX "+
            "XML_ATTRIBUTE_LIMIT XML_BYTES_LIMIT XML_DEPTH XML_DTD XML_ENTITY XML_FEATURES XML_FORMAT XML_PARSER_SETUP XML_HTTP_STATUS XML_TOKEN_LIMIT").split(" ")));
    private SourceFailureInfo(Kind kind,int status,RetryAfterParser.Kind retry,long until,String reason) {
        this.kind=kind;this.status=status;retryAfterKind=retry;retryAtMonotonicMillis=until;this.reason=reason;
    }
    public static SourceFailureInfo from(Exception error,SourceClock clock) {
        if(error instanceof HttpStatusFailure) {
            HttpStatusFailure http=(HttpStatusFailure)error;
            RetryAfterParser.Result retry=http.retryAfter;
            boolean excessive=retry.kind==RetryAfterParser.Kind.EXCESSIVE;
            long until=excessive?Long.MAX_VALUE:retry.kind==RetryAfterParser.Kind.DELAY
                    ?SourceClock.saturatedAdd(http.receivedMonotonicMillis,retry.delayMillis):0;
            return new SourceFailureInfo(excessive?Kind.EXCESSIVE:Kind.HTTP,http.status,retry.kind,until,http.reason);
        }
        if(error instanceof CooldownFailure) {
            CooldownFailure cooldown=(CooldownFailure)error;
            return new SourceFailureInfo(cooldown.excessive?Kind.EXCESSIVE:Kind.COOLDOWN,cooldown.status,
                    cooldown.excessive?RetryAfterParser.Kind.EXCESSIVE:RetryAfterParser.Kind.DELAY,
                    cooldown.excessive?Long.MAX_VALUE:SourceClock.saturatedAdd(cooldown.receivedMonotonicMillis,cooldown.remainingMillis),cooldown.reason);
        }
        String reason=error instanceof SourceFailure?((SourceFailure)error).reason:error instanceof IllegalArgumentException?"INVALID_OR_LIMIT":error instanceof IllegalStateException?"STORAGE_OR_LIMIT":"SOURCE_UNAVAILABLE";
        return forReason(reason);
    }
    public static SourceFailureInfo forReason(String reason) {
        int status=0;
        if(reason!=null&&reason.matches("HTTP_[1-5][0-9]{2}"))status=Integer.parseInt(reason.substring(5));
        Kind kind=status>0?Kind.HTTP:"TIMEOUT".equals(reason)?Kind.TIMEOUT:"CANCELLED".equals(reason)?Kind.CANCELLED:
                "COOLDOWN_UNREPRESENTABLE".equals(reason)?Kind.EXCESSIVE:reason!=null&&(reason.contains("STORAGE")||reason.contains("PERSIST"))?Kind.STORAGE:
                "NETWORK_ERROR".equals(reason)?Kind.NETWORK:reason!=null&&(reason.startsWith("URL_")||reason.startsWith("HTTPS_")||reason.equals("INVALID_OR_LIMIT"))?Kind.INVALID:Kind.UNAVAILABLE;
        return new SourceFailureInfo(kind,status,kind==Kind.EXCESSIVE?RetryAfterParser.Kind.EXCESSIVE:RetryAfterParser.Kind.ABSENT,kind==Kind.EXCESSIVE?Long.MAX_VALUE:0,reason);
    }
    /** Bounded diagnostic payload in the existing lastError string; no throwable/response text. */
    public String downloadError() {
        if(!downloadReason(reason)||status!=0&&(status<100||status>599))return "DL1|UNAVAILABLE|0|SOURCE_UNAVAILABLE";
        return DOWNLOAD_PREFIX+kind.name()+"|"+status+"|"+reason;
    }
    private static boolean downloadReason(String reason){return reason!=null&&reason.length()<=64&&(DOWNLOAD_REASONS.contains(reason)||reason.matches("HTTP_[1-5][0-9]{2}"));}
    private static SourceFailureInfo storedDownload(String stored){
        if(stored==null||stored.length()>128||!stored.startsWith(DOWNLOAD_PREFIX))return null;
        String[] parts=stored.split("\\|",-1);if(parts.length!=4||!parts[2].matches("0|[1-5][0-9]{2}")||!downloadReason(parts[3]))return null;
        try{
            Kind kind=Kind.valueOf(parts[1]);int status=Integer.parseInt(parts[2]);
            if(parts[3].startsWith("HTTP_")&&Integer.parseInt(parts[3].substring(5))!=status)return null;
            if(kind==Kind.HTTP&&(!parts[3].startsWith("HTTP_")||status==0))return null;
            if(parts[3].startsWith("HTTP_")&&kind!=Kind.HTTP&&kind!=Kind.EXCESSIVE)return null;
            // Retry admission/deadlines remain exclusively in the existing transport cooldown store.
            return new SourceFailureInfo(kind,status,RetryAfterParser.Kind.ABSENT,0,parts[3]);
        }catch(IllegalArgumentException invalid){return null;}
    }
    public static SourceFailureInfo forStoredError(String stored){
        SourceFailureInfo download=storedDownload(stored);
        return download!=null?download:forReason(stored!=null&&stored.startsWith(DOWNLOAD_PREFIX)?"SOURCE_UNAVAILABLE":stored);
    }
    public static String downloadDiagnostic(String stored){SourceFailureInfo info=storedDownload(stored);return info==null?null:info.downloadError();}
    public SourceFailureInfo withStatus(int status) {
        return new SourceFailureInfo(kind,status,retryAfterKind,retryAtMonotonicMillis,reason);
    }
    public long remainingMillis(SourceClock clock) {
        return kind==Kind.EXCESSIVE?Long.MAX_VALUE:Math.max(0,retryAtMonotonicMillis-clock.monotonicMillis());
    }
    public boolean retryBlocked(SourceClock clock) { return remainingMillis(clock)>0; }
    public SourceFailure rejection(SourceClock clock) {
        return new CooldownFailure(kind==Kind.EXCESSIVE?"COOLDOWN_UNREPRESENTABLE":"HOST_COOLDOWN",
                remainingMillis(clock),clock,kind==Kind.EXCESSIVE,status);
    }
    public String message(SourceClock clock) {
        if("HTML_INDEX_UNREADABLE".equals(reason))return "Не удалось подтвердить список публикаций в разделе. Проверьте ссылку на раздел или RSS.";
        if("HTML_INDEX_ARTICLE_IDENTITY".equals(reason))return "Страница не соответствует выбранной публикации. Откройте исходную ссылку или обновите список.";
        if("HTML_PAGE_EMPTY".equals(reason))return "На странице не найден читаемый основной текст. Источник не добавлен; попробуйте другую открытую ссылку.";
        if("HTML_PAGE_ACCESS_REQUIRED".equals(reason))return "Страница ограничивает доступ или требует проверку. Откройте публичную страницу без входа, подписки и CAPTCHA.";
        if("HTML_PAGE_UNSUPPORTED".equals(reason))return "Ссылка не вернула читаемую HTML-страницу. Проверьте адрес или попробуйте другую открытую страницу.";
        if(kind==Kind.EXCESSIVE)return "Сервер указал срок ожидания, который невозможно безопасно представить. Запросы к этому серверу заблокированы; сохранённые тексты доступны. Автоматического повтора нет.";
        long remaining=remainingMillis(clock);
        if(remaining>0) {
            long seconds=remaining/1000+(remaining%1000==0?0:1);
            String time=seconds<60?seconds+" сек.":seconds<3600?(seconds/60+(seconds%60==0?0:1))+" мин.":(seconds/3600+(seconds%3600==0?0:1))+" ч.";
            return (status>0?"Сервер временно недоступен ("+status+"). ":"Сервер просит подождать. ")+"Повторить вручную можно через "+time+". Сохранённые тексты доступны.";
        }
        switch(kind) {
            case HTTP:return "Сервер вернул ошибку "+status+". Последние успешные данные сохранены; повтор выполняется вручную.";
            case COOLDOWN:return "Срок ожидания истёк. Можно повторить запрос вручную.";
            case STORAGE:return "Не удалось подтвердить сохранение. Файлы сохранены; новые изменения временно заблокированы.";
            case TIMEOUT:return "Сервер не ответил вовремя. Можно повторить проверку вручную.";
            case CANCELLED:return "Проверка отменена. Сохранённые данные остаются.";
            case NETWORK:return "Не удалось связаться с сервером. Проверьте сеть и повторите вручную.";
            case INVALID:return "Ссылка или данные не подходят для этой проверки. Проверьте HTTP(S)-адрес и формат источника.";
            default:return "Источник или текст сейчас недоступен. Последние успешные данные сохранены.";
        }
    }
}
