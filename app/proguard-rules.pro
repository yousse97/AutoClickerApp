# Flatten every class into one package and drop source details from stack traces.
-repackageclasses
-allowaccessmodification
-renamesourcefileattribute ""

# Annotations that Tink references only at compile time.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
