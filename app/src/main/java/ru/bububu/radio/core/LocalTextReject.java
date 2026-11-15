package ru.bububu.radio.core;

import java.util.Set;

/** Only deterministic input rejections; model/data/IO/media errors are never inferred as text. */
public final class LocalTextReject {
    private static final String PREFIX="LOCAL_TEXT_REJECTED:";
    private static final Set<String> CODES=Set.of("UNSUPPORTED_MULTIPLE_HOMOGRAPHS","UNSUPPORTED_SOURCE_ATOM","UNSUPPORTED_RADIO_ATOM","UNSUPPORTED_SOURCE_NOTATION","UNSUPPORTED_SOURCE_CITATION_RANGE","UNSUPPORTED_SOURCE_URL_CHARACTER","UNSUPPORTED_SOURCE_CURRENCY_PROFILE","UNSUPPORTED_SOURCE_CALENDAR_DATE","UNSUPPORTED_SOURCE_SUPERSCRIPT_FORM","UNSUPPORTED_FRONTEND_CHARACTERS","UNSUPPORTED_CHARACTER","NGRAM_WORD_QUADRATIC_BOUND","ACCENTOR_TOKEN_BOUND","UNSUPPORTED_SPOKEN_BOUND",
            "UNSUPPORTED_SOURCE_CURRENCY_SUFFIX","UNSUPPORTED_SOURCE_DECIMAL_PRECISION","UNSUPPORTED_SOURCE_DOTTED_NUMBER","UNSUPPORTED_SOURCE_GENITIVE_DECIMAL_BOUND","UNSUPPORTED_SOURCE_INTEGER_LENGTH","UNSUPPORTED_SOURCE_ORDINAL_CASE","UNSUPPORTED_SOURCE_UNBOUND_URL","UNSUPPORTED_SOURCE_YEAR_ZERO","UNSUPPORTED_SOURCE_ATOM_BOUND",
            "UNSUPPORTED_RADIO_DATE","UNSUPPORTED_RADIO_DECIMAL_PERCENT","UNSUPPORTED_RADIO_GENITIVE_DECIMAL_PERCENT","UNSUPPORTED_RADIO_INTEGER","UNSUPPORTED_RADIO_NUMBER_CONTEXT","UNSUPPORTED_RADIO_ORDINAL","UNSUPPORTED_RADIO_PERCENT","UNSUPPORTED_RADIO_SIGNED_OR_RANGE","UNSUPPORTED_RADIO_YEAR","UNSUPPORTED_RADIO_YEAR_CASE");
    private LocalTextReject(){}
    public static boolean known(String code){return code!=null&&(CODES.contains(code)||code.matches("UNSUPPORTED_SOURCE_LITERAL_U[0-9a-f]{1,4}"));}
    public static String preflight(Throwable failure){return failure instanceof IllegalArgumentException&&known(failure.getMessage())?failure.getMessage():null;}
    public static String prepared(String message){if(message==null)return null;String code=message.startsWith(PREFIX)?message.substring(PREFIX.length()):message.startsWith("Silero: ")?message.substring(8):null;return known(code)?code:null;}
    public static String tagged(String code){if(!known(code))throw new IllegalArgumentException("Not a text rejection");return PREFIX+code;}
    /** Safe fixed diagnostic code; unlike known(), this grants no recovery permission. */
    public static String diagnostic(String message){if(message==null)return "NON_TEXT_FAILURE";String code=message.startsWith(PREFIX)?message.substring(PREFIX.length()):message.startsWith("Silero: ")?message.substring(8):message;return code.matches("[A-Z][A-Z0-9_]{0,79}")?code:"NON_TEXT_FAILURE";}
}
