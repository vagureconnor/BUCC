package app.bucc.homework

import app.bucc.api.feature.JudgeApi
import app.bucc.api.feature.SpocApi
import app.bucc.model.dto.*

class SpocHomeworkAdapter(private val api: SpocApi = SpocApi()) : HomeworkAdapter {
  override val source = HomeworkSource.SPOC

  override suspend fun load() =
      api.getAssignments().getOrThrow().assignments.map { item ->
        HomeworkItem(
            id = "spoc:${item.courseId}:${item.assignmentId}",
            source = source,
            upstreamId = item.assignmentId,
            title = item.title,
            courseId = item.courseId,
            courseName = item.courseName,
            startAt = parseHomeworkDate(item.startTime),
            dueAt = parseHomeworkDate(item.dueTime),
            submission = item.submissionStatus.toHomework(),
            url = "https://spoc.buaa.edu.cn/spocnew/common/zycskzy",
        )
      }

  override suspend fun detail(item: HomeworkItem): HomeworkItem {
    val detail = api.getAssignmentDetail(item.upstreamId).getOrThrow()
    return item.copy(
        content = detail.contentPlainText.orEmpty(),
        submission = detail.submissionStatus.toHomework(),
    )
  }
}

class JudgeHomeworkAdapter(
    private val account: String,
    private val api: JudgeApi = JudgeApi(),
    private val semester: suspend () -> HomeworkSemester = HomeworkSemesterProvider()::load,
) : HomeworkAdapter {
  override val source = HomeworkSource.JUDGE

  override suspend fun load(): List<HomeworkItem> {
    val current = semester()
    val response = api.getAssignments(includeExpired = true, userKey = account).getOrThrow()
    val summaries =
        response.assignments
            .onEach { item ->
              check(
                  parseHomeworkDate(item.startTime) != null ||
                      parseHomeworkDate(item.dueTime) != null
              ) {
                "希冀作业日期缺失或格式异常：${item.assignmentId}（${item.title}），请刷新后重试"
              }
            }
            .filter {
              current.contains(parseHomeworkDate(it.startTime), parseHomeworkDate(it.dueTime))
            }
            .distinctBy { it.courseId to it.assignmentId }
    val items =
        summaries.map { item ->
          val count = item.submittedCount
          val total = item.totalProblems
          val status =
              when {
                total > 0 && count >= total -> HomeworkSubmission.SUBMITTED
                count > 0 -> HomeworkSubmission.PARTIAL
                item.submissionStatus == JudgeSubmissionStatus.UNKNOWN -> HomeworkSubmission.UNKNOWN
                else -> HomeworkSubmission.UNSUBMITTED
              }
          HomeworkItem(
              id = "judge:${item.courseId}:${item.assignmentId}",
              source = source,
              upstreamId = item.assignmentId,
              title = item.title,
              courseId = item.courseId,
              courseName = item.courseName,
              startAt = parseHomeworkDate(item.startTime),
              dueAt = parseHomeworkDate(item.dueTime),
              submission = status,
              url = "https://judge.buaa.edu.cn/main.jsp",
          )
        }
    if (response.failedCourseIds.isNotEmpty()) {
      throw HomeworkPartialLoadException(
          items,
          response.failedCourseIds.toSet(),
          "${response.failedCourseIds.size} 门希冀课程更新失败，已保留上次记录，请重试",
      )
    }
    return items
  }

  override suspend fun detail(item: HomeworkItem): HomeworkItem {
    val detail = api.getAssignmentDetail(item.courseId, item.upstreamId).getOrThrow()
    return item.copy(content = detail.contentPlainText.orEmpty())
  }
}

private fun SpocSubmissionStatus.toHomework() =
    when (this) {
      SpocSubmissionStatus.SUBMITTED -> HomeworkSubmission.SUBMITTED
      SpocSubmissionStatus.UNSUBMITTED -> HomeworkSubmission.UNSUBMITTED
      SpocSubmissionStatus.UNKNOWN -> HomeworkSubmission.UNKNOWN
    }
