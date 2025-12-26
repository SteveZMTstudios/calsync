package top.stevezmt.calsync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions

internal class MLKitStrategy(private val context: Context) : ParsingStrategy {
    override fun name() = "ML Kit"
    override fun tryParse(sentence: String): DateTimeParser.ParseResult? = tryParseWithBase(sentence, DateTimeParser.getNowMillis())

    fun tryParseWithBase(sentence: String, baseMillis: Long): DateTimeParser.ParseResult? {
        return try {
            Log.d("MLKitStrategy", "Starting ML Kit parsing for: $sentence")
            val options = EntityExtractorOptions.Builder(EntityExtractorOptions.CHINESE).build()
            val extractor = EntityExtraction.getClient(options)

            // Note: This may require Google Play Services to download the model on first run.
            // The download is typically handled by GMS in the background.
            Log.d("MLKitStrategy", "Checking/Downloading ML Kit model...")
            Tasks.await(extractor.downloadModelIfNeeded())
            Log.d("MLKitStrategy", "Model is ready.")

            val params = EntityExtractionParams.Builder(sentence)
                .setReferenceTime(baseMillis)
                .build()

            Log.d("MLKitStrategy", "Annotating text...")
            val annotations = Tasks.await(extractor.annotate(params))
            Log.d("MLKitStrategy", "Found ${annotations.size} annotations.")

            var start: Long? = null
            var end: Long? = null
            var loc: String? = null

            for (annotation in annotations) {
                for (entity in annotation.entities) {
                    when {
                        entity is DateTimeEntity -> {
                            if (start == null) {
                                start = entity.timestampMillis
                            } else if (end == null) {
                                end = entity.timestampMillis
                            }
                        }
                        entity.type == Entity.TYPE_ADDRESS -> {
                            loc = annotation.annotatedText
                        }
                    }
                }
            }

            if (start != null) {
                DateTimeParser.ParseResult(start, end, null, loc)
            } else null
        } catch (e: Exception) {
            Log.w("MLKitStrategy", "ML Kit parsing failed: ${e.message}")
            null
        }
    }

    fun extractTitleAndLocation(sentence: String): Pair<String?, String?> {
        return try {
            Log.d("MLKitStrategy", "Extracting title/loc using ML Kit: $sentence")
            val options = EntityExtractorOptions.Builder(EntityExtractorOptions.CHINESE).build()
            val extractor = EntityExtraction.getClient(options)
            
            Log.d("MLKitStrategy", "Checking/Downloading ML Kit model for extraction...")
            Tasks.await(extractor.downloadModelIfNeeded())
            
            val params = EntityExtractionParams.Builder(sentence).build()
            val annotations = Tasks.await(extractor.annotate(params))
            Log.d("MLKitStrategy", "Extraction found ${annotations.size} annotations.")

            var loc: String? = null
            for (annotation in annotations) {
                for (entity in annotation.entities) {
                    if (entity.type == Entity.TYPE_ADDRESS) {
                        loc = annotation.annotatedText
                    }
                }
            }
            Pair(null, loc)
        } catch (e: Exception) {
            Log.w("MLKitStrategy", "ML Kit extraction failed: ${e.message}")
            Pair(null, null)
        }
    }
}
