package app.bucc.ui.screens.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bucc.model.dto.CourseClass
import app.bucc.model.dto.SigninClassDto

@Composable
fun CourseDetailScreen(
    course: CourseClass,
    signinCourse: SigninClassDto? = null,
    isLoadingSigninClasses: Boolean = false,
    isSigningIn: Boolean = false,
    signinResult: String? = null,
    signinError: String? = null,
    onSigninClick: () -> Unit = {},
    onRetrySignin: () -> Unit = {},
    onOpenSigninList: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
  LazyColumn(
      modifier = modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
              CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
              text = course.courseName,
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
          )

          Text(
              text = "课程代码：${course.courseCode}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
    }

    item {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text("课堂打卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          when {
            isLoadingSigninClasses ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                  CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                  Spacer(Modifier.width(8.dp))
                  Text("正在加载今日可签到课程…")
                }
            signinError != null -> {
              Text(signinError, color = MaterialTheme.colorScheme.error)
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetrySignin) { Text("重试") }
                OutlinedButton(onClick = onOpenSigninList) { Text("查看今日课堂") }
              }
            }
            signinCourse == null -> {
              Text(
                  "今日没有找到与这门课匹配的可签到班次。",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              OutlinedButton(onClick = onOpenSigninList) { Text("查看今日课堂") }
            }
            signinCourse.signStatus == 1 ->
                Text("本课程已签到。", color = MaterialTheme.colorScheme.primary)
            else ->
                Button(
                    onClick = onSigninClick,
                    enabled = !isSigningIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                  if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在打卡…")
                  } else {
                    Text("一键打卡")
                  }
                }
          }
          signinResult?.let { result ->
            Text(
                result,
                color =
                    if (result == "签到成功") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
          }
        }
      }
    }

    item {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
              text = "基本信息",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
          )

          course.courseSerialNo?.let { serialNo -> CourseInfoRow("课程序号", serialNo) }

          course.credit?.let { credit -> CourseInfoRow("学分", credit) }

          course.placeName?.let { place -> CourseInfoRow("上课地点", place) }

          if (course.beginTime != null && course.endTime != null) {
            CourseInfoRow("上课时间", "${course.beginTime} - ${course.endTime}")
          }

          if (course.beginSection != null && course.endSection != null) {
            val sectionText =
                if (course.beginSection == course.endSection) {
                  "第${course.beginSection}节"
                } else {
                  "第${course.beginSection}-${course.endSection}节"
                }
            CourseInfoRow("节次", sectionText)
          }

          course.dayOfWeek?.let { dayOfWeek ->
            val dayName =
                when (dayOfWeek) {
                  1 -> "周一"
                  2 -> "周二"
                  3 -> "周三"
                  4 -> "周四"
                  5 -> "周五"
                  6 -> "周六"
                  7 -> "周日"
                  else -> "未知"
                }
            CourseInfoRow("星期", dayName)
          }
        }
      }
    }

    if (course.credit != null) {
      item {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
                text = "详细信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            course.weeksAndTeachers?.let { weeksAndTeachers ->
              CourseInfoRow("周次/教师", weeksAndTeachers)
            }

            course.teachingTarget?.let { teachingTarget -> CourseInfoRow("教学对象", teachingTarget) }
          }
        }
      }
    }
  }
}

@Composable
private fun CourseInfoRow(label: String, value: String, modifier: Modifier = Modifier) {
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(0.3f),
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(0.7f),
    )
  }
}
