package app.koreshok.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Cloth colors for generated covers: [background, foil]. */
private val Cloths = listOf(
    Color(0xFF1E3A34) to Color(0xFFE3C48F),
    Color(0xFF5B2333) to Color(0xFFEBCB9A),
    Color(0xFF1F3550) to Color(0xFFD9C7A0),
    Color(0xFF7A4A1E) to Color(0xFFF3DDB5),
    Color(0xFF3B3F45) to Color(0xFFE0C9A6),
    Color(0xFF4A3B5C) to Color(0xFFE8D2A8),
    Color(0xFF2E4A2A) to Color(0xFFE6D3A3),
    Color(0xFF8C3B2A) to Color(0xFFF5DFC0),
)

/**
 * A book cover with a soft shadow and rounded spine side. Without artwork it draws a cloth
 * binding in a color picked from the title, with the title stamped in foil.
 */
@Composable
fun BookCover(
    title: String,
    author: String?,
    image: Any?,
    modifier: Modifier = Modifier,
    elevation: Dp = 6.dp,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    Box(
        modifier
            .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape),
    ) {
        if (image != null) {
            val (cloth, _) = Cloths[(title.hashCode() and Int.MAX_VALUE) % Cloths.size]
            AsyncImage(
                model = image,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(cloth),
            )
        } else {
            GeneratedCover(title, author, compact)
        }
        // Spine shading on the left edge makes a flat image read as a book.
        Box(
            Modifier
                .fillMaxHeight()
                .width(10.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.28f),
                        0.35f to Color.White.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                    ),
                ),
        )
    }
}

@Composable
private fun GeneratedCover(title: String, author: String?, compact: Boolean) {
    val (cloth, foil) = Cloths[(title.hashCode() and Int.MAX_VALUE) % Cloths.size]
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(cloth.copy(alpha = 0.92f), cloth)),
            )
            .padding(if (compact) 5.dp else 8.dp)
            .border(0.8.dp, foil.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
            .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 8.dp else 12.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (!author.isNullOrBlank()) {
                Text(
                    author.uppercase(),
                    color = foil.copy(alpha = 0.85f),
                    style = TextStyle(fontSize = if (compact) 6.sp else 8.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.height(1.dp))
            }
            Text(
                title,
                color = foil,
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (compact) 10.sp else 15.sp,
                    lineHeight = if (compact) 12.sp else 18.sp,
                ),
                textAlign = TextAlign.Center,
                maxLines = if (compact) 4 else 5,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.width(18.dp).height(0.8.dp).background(foil.copy(alpha = 0.6f)))
        }
    }
}

/** Two-line title and author used under covers on the shelf, the search results and catalogs. */
@Composable
fun CoverCaption(title: String, author: String?, modifier: Modifier = Modifier, titleStyle: TextStyle, authorColor: Color) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!author.isNullOrBlank()) {
            Text(
                author,
                style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp),
                color = authorColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
