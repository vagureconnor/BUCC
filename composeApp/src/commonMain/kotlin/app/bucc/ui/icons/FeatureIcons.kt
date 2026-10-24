package app.bucc.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class FeatureIcon(val vector: ImageVector) {
  SCHEDULE(Icons.Default.CalendarToday),
  EXAM(Icons.AutoMirrored.Filled.Assignment),
  GRADE(Icons.Default.Grade),
  BYKC(Icons.Default.School),
  ATTENDANCE(Icons.AutoMirrored.Filled.FactCheck),
  HOMEWORK(Icons.Default.AssignmentTurnedIn),
  EVALUATION(Icons.Default.RateReview),
  CLASSROOM(Icons.Default.MeetingRoom),
  LIBRARY(Icons.AutoMirrored.Filled.MenuBook),
  TD(Icons.AutoMirrored.Filled.DirectionsRun),
  CGYY(Icons.Default.EventAvailable),
  YGDK(Icons.Default.WbSunny),
  SUPPORT(Icons.Default.SupportAgent),
  PAPER_PLANE(Icons.AutoMirrored.Filled.Send),
  FINANCE(Icons.Default.AccountBalanceWallet),
  BYKC_SELECT(Icons.Default.AutoStories),
  BYKC_COURSES(Icons.Default.BookmarkAdded),
  BYKC_STATS(Icons.Default.Insights),
  CGYY_BOOKINGS(Icons.AutoMirrored.Filled.EventNote),
  LIBRARY_SEAT(Icons.Default.EventSeat),
  LIBRARY_SEMINAR(Icons.Default.Groups),
  LIBRARY_SCAN(Icons.Default.QrCode2),
  LIBRARY_BOOKINGS(Icons.Default.Bookmarks),
  TODO_BYKC(Icons.Default.HowToReg),
  TODO_JUDGE(Icons.Default.Code),
  TODO_ACCODING(Icons.Default.Terminal),
  TODO_PATPAT(Icons.Default.Forum),
  TODO_BYXT(Icons.Default.Science),
  TODO_SIGNIN(Icons.Default.CheckCircle),
}
