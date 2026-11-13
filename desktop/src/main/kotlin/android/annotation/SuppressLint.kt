package android.annotation

/** Nur für Lint auf Android – auf dem Desktop wirkungslos. */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.EXPRESSION, AnnotationTarget.FILE, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class SuppressLint(vararg val value: String)

@Retention(AnnotationRetention.SOURCE)
annotation class TargetApi(val value: Int)
