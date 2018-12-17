package com.theta360.oculuswebui;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.util.Log;
import com.samskivert.mustache.Mustache;
import com.theta360.oculuswebui.network.HttpConnector;
import com.theta360.oculuswebui.network.ImageInfo;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebServer extends NanoHTTPD {

  private static final String IP_ADDRESS_AP = "192.168.1.1";
  private static final int PORT = 8888;
  private static final String CAMERA_ADDRESS = "127.0.0.1:8080";
  private static final String INDEX_TEMPLATE_FILE_NAME = "index_template.html";
  private static final String INDEX_OUTPUT_FILE_NAME = "index_out.html";
  private static final int NUM_OF_FILES = 12;
  private static final String DCIM = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getPath();
  private final String LINE_BRAKE = System.getProperty("line.separator");
  private String ipAddress = IP_ADDRESS_AP;
  private String serverUrl = "";
  private Context context;
  private HttpConnector theta;

  public WebServer(Context context) {
    super(PORT);
    this.context = context;
    this.ipAddress = this.getWifiIPAddress(context);
    this.theta = new HttpConnector(CAMERA_ADDRESS);
    this.serverUrl = "http://" + this.ipAddress + ":" + PORT;
    Log.i("WebServer", this.serverUrl);
  }

  @Override
  public Response serve(IHTTPSession session) {
    Method method = session.getMethod();
    String uri = session.getUri();
    switch (method) {
      case GET:
        return this.serveFile(uri);
      case POST:
      default:
        return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain",
            "Method [" + method + "] is not allowed.");
    }
  }

  private Response serveFile(String uri) {
    if ("/".equals(uri)) {
      return this.newHtmlResponse(this.generateIndexHtmlContext(this.getImageInfos()),
          INDEX_TEMPLATE_FILE_NAME, INDEX_OUTPUT_FILE_NAME);
    } else if (uri.startsWith("/files")) {
      return this.newFilesResponse(uri);
    } else if (uri.startsWith("/download")) {
      return this.newDownloadResponse(uri);
    } else {
      return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "URI [" + uri + "] is not found.");
    }
  }

  private Response newHtmlResponse(Map<String, Object> data, String templateFileName, String outFileName) {
    AssetManager assetManager = context.getAssets();
    try(InputStreamReader template = new InputStreamReader(assetManager.open(templateFileName));
        OutputStreamWriter output = new OutputStreamWriter(this.context.openFileOutput(outFileName, Context.MODE_PRIVATE))) {
      Mustache.compiler().compile(template).execute(data, output);
      return newChunkedResponse(Status.OK, "text/html", this.context.openFileInput(outFileName));
    } catch (IOException e) {
      e.printStackTrace();
      return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.getMessage());
    }
  }

  private Map<String, Object> generateIndexHtmlContext(List<ImageInfo> imageInfos) {
    Map<String, Object> context = new HashMap<>();
    int fileCount = 0;
    StringBuilder sb = new StringBuilder();
    for(ImageInfo imageInfo : imageInfos) {
      switch (imageInfo.getFileFormat()) {
        case ImageInfo.FILE_FORMAT_CODE_EXIF_JPEG:
          sb.append(this.generateImgElement(imageInfo));
          fileCount++;
          break;
        case ImageInfo.FILE_FORMAT_CODE_EXIF_MPEG:
          if(ImageInfo.PROJECTION_TYPE_EQUI.equals(imageInfo.getProjectionType())) {
            sb.append(this.generateVideoElement(imageInfo));
            fileCount++;
          }
          break;
          default: break;
      }
      if(fileCount > NUM_OF_FILES - 1) break;
    }
    context.put("fileElements", sb.toString());
    context.put("numOfFiles", String.valueOf(fileCount));
    return context;
  }

  private String generateImgElement(ImageInfo imageInfo) {
    StringBuilder sb = new StringBuilder();
    String fileId = imageInfo.getFileId();
    String fileUrl = this.generateFileUrlThroughWebServer(fileId);
    String downloadUrl = this.generateDownloadUrl(fileId);
    sb.append("<div class=\"child\">").append(LINE_BRAKE)
        .append("<img src=\"").append(fileUrl).append("\" class=t360 width=300 onclick=\"javascript:this.webkitRequestFullscreen()\"/>").append(LINE_BRAKE)
        .append("<a href=\"").append(downloadUrl).append("\">Download</a>").append(LINE_BRAKE)
        .append("</div>").append(LINE_BRAKE);
    return sb.toString();
  }

  private String generateVideoElement(ImageInfo imageInfo) {
    StringBuilder sb = new StringBuilder();
    String fileUrl = this.generateFileUrlThroughWebServer(imageInfo.getFileId());
    sb.append("<div class=\"child\">").append(LINE_BRAKE)
        .append("<video class=t360 width=300 onclick=\"javascript:this.webkitRequestFullscreen()\" controls>").append(LINE_BRAKE)
        .append("<source src=\"").append(fileUrl).append("\">").append(LINE_BRAKE)
        .append("</video>").append(LINE_BRAKE)
        .append("</div>").append(LINE_BRAKE);
    return sb.toString();
  }

  private Response newFilesResponse(String uri) {
    try {
      return newChunkedResponse(Status.OK, getMimeTypeForFile(uri), this.getLocalFileInputStream(uri));
    } catch (IOException e) {
      e.printStackTrace();
    }
    return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain",
        uri + " is illegal file URI.");
  }

  private Response newDownloadResponse(String uri) {
    try {
      Response res = newChunkedResponse(Status.OK, getMimeTypeForFile(uri),
          this.getLocalFileInputStream(uri));
      res.addHeader("Content-Type", "application/force-download");
      int index = uri.lastIndexOf("/");
      // Image file extension should be lower case to show by VR view as default in gallery app.
      String fileName = uri.substring(index + 1).replaceAll("JPG", "jpg");
      res.addHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
      return res;
    } catch (IOException e) {
      e.printStackTrace();
    }
    return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain",
        uri + " is illegal download URI.");
  }

  private InputStream getLocalFileInputStream(String uri) throws IOException {
    Matcher matcher = Pattern.compile("/\\d{3}RICOH.*").matcher(uri);
    if (matcher.find()) {
      String localFileUrl = DCIM + matcher.group();
      try {
        FileInputStream fileInputStream = new FileInputStream(localFileUrl);
        return fileInputStream;
      } catch (IOException e) {
        throw e;
      }
    }
    throw new IllegalArgumentException("Illegal URI: " + uri);
  }
  
  private String generateFileUrlThroughWebServer(String fileId) {
    Matcher matcher = Pattern.compile("/\\d{3}RICOH.*").matcher(fileId);
    if (matcher.find()) {
      // Replace direct THETA file URL into web server URL
      return this.serverUrl + "/files" + matcher.group();
    }
    return fileId;
  }

  private String generateDownloadUrl(String fileId) {
    Matcher matcher = Pattern.compile("/\\d{3}RICOH.*").matcher(fileId);
    if (matcher.find()) {
      return this.serverUrl + "/download" + matcher.group();
    }
    return fileId;
  }

  private List<ImageInfo> getImageInfos() {
    List<ImageInfo> imageInfos = this.theta.getList();
    if(imageInfos == null || imageInfos.isEmpty()) {
      return Collections.emptyList();
    }
    return imageInfos;
  }

  private String getWifiIPAddress(Context context) {
    WifiManager manager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
    if(manager == null) {
      return IP_ADDRESS_AP;
    }
    WifiInfo info = manager.getConnectionInfo();
    int ipAddressNum = info.getIpAddress();
    if(ipAddressNum == 0){
      return IP_ADDRESS_AP;
    }
    String ipAddress = String.format(Locale.US, "%02d.%02d.%02d.%02d",
        (ipAddressNum>>0)&0xff, (ipAddressNum>>8)&0xff, (ipAddressNum>>16)&0xff, (ipAddressNum>>24)&0xff);
    return ipAddress;
  }

}
