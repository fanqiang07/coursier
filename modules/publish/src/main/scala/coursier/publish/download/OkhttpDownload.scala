package coursier.publish.download

import java.time.Instant
import java.util.concurrent.ExecutorService

import com.squareup.okhttp.internal.http.HttpDate
import com.squareup.okhttp.{OkHttpClient, Request, Response}
import coursier.cache.CacheUrl
import coursier.core.Authentication
import coursier.publish.download.logger.DownloadLogger
import coursier.util.Task

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

final case class OkhttpDownload(client: OkHttpClient, pool: ExecutorService) extends Download {

  import OkhttpDownload.TryOps

  def downloadIfExists(url: String, authentication: Option[Authentication], logger: DownloadLogger): Task[Option[(Option[Instant], Array[Byte])]] = {

    // FIXME Some duplication with upload below…

    val request = {
      val b = new Request.Builder()
        .url(url)
        .get()

      // Handling this ourselves rather than via client.setAuthenticator / com.squareup.okhttp.Authenticator
      for (auth <- authentication)
        b.addHeader("Authorization", "Basic " + CacheUrl.basicAuthenticationEncode(auth.user, auth.password))

      b.build()
    }

    Task.schedule(pool) {
      logger.downloadingIfExists(url)

      val res = Try {
        var response: Response = null

        try {
          response = client.newCall(request).execute()

          if (response.isSuccessful) {
            val lastModifiedOpt = Option(response.header("Last-Modified")).map { s =>
              HttpDate.parse(s).toInstant
            }
            Right(Some((lastModifiedOpt, response.body().bytes())))
          } else {
            val code = response.code()
            if (code == 404)
              Right(None)
            else if (code == 401) {
              val realmOpt = Option(response.header("WWW-Authenticate")).collect {
                case CacheUrl.BasicRealm(r) => r
              }
              Left(new Download.Error.Unauthorized(url, realmOpt))
            } else {
              val content = Try(response.body().string()).getOrElse("")
              Left(new Download.Error.HttpError(code, response.headers().toMultimap.asScala.mapValues(_.asScala.toList).iterator.toMap, content))
            }
          }
        } finally {
          if (response != null)
            response.body().close()
        }
      }.toEither.right.flatMap(identity)

      logger.downloadedIfExists(
        url,
        res.right.toOption.flatMap(_.map(_._2.length)),
        res.left.toOption.map(e => new Download.Error.DownloadError(e))
      )

      Task.fromEither(res)
    }.flatMap(identity)
  }

}

object OkhttpDownload {

  // for 2.11
  private[publish] implicit class TryOps[T](private val t: Try[T]) {
    def toEither: Either[Throwable, T] =
      t match {
        case Success(t) => Right(t)
        case Failure(e) => Left(e)
      }
  }

  def create(pool: ExecutorService): Download = {
    // Seems we can't even create / shutdown the client thread pool (via its Dispatcher)…
    OkhttpDownload(new OkHttpClient, pool)
  }
}
