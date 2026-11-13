package androidx.annotation

/** Nur Kennzeichnung auf Android – auf dem Desktop wirkungslos. */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD,
    AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FILE, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class RequiresApi(val value: Int = 1, val api: Int = 1)

@Retention(AnnotationRetention.SOURCE)
annotation class ColorInt

@Retention(AnnotationRetention.SOURCE)
annotation class MainThread

@Retention(AnnotationRetention.SOURCE)
annotation class WorkerThread
