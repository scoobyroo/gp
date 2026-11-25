/** Unchecked error raised when an XML export cannot be read or is not well-formed. Carries the file name. */
final class XmlFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Name of the XML file that could not be read (for example GP_PIN.xml). */
    final String file;

    XmlFormatException(String file, String detail) {
        super(file + ": " + (detail == null ? "cannot read XML" : detail));
        this.file = file;
    }

    XmlFormatException(String file, String detail, Throwable cause) {
        this(file, detail);
        initCause(cause);
    }
}
