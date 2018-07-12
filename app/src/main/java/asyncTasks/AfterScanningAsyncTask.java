package asyncTasks;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import com.itextpdf.text.pdf.PdfReader;
import net.scaniq.scaniqairprint.R;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import helperClasses.AlertBoxBuilder;
import helperClasses.BarcodeProcessor;
import helperClasses.DatabaseManager;
import helperClasses.EmailSender;
import helperClasses.LocalFileManager;
import helperClasses.SharedPreferencesManager;
import helperClasses.SoundPlayer;
import helperClasses.WifiHelper;

import static net.scaniq.scaniqairprint.ScaniqMainActivity.serialNumber;


public class AfterScanningAsyncTask extends AsyncTask<String, String, String> {

    private Context context;
    private LocalFileManager mLocalFileManager;
    private SoundPlayer mSoundPlayer;
    private ProgressDialog dialog;
    private WifiHelper wifi;
    private String ccMail;
    private String bccMail;
    private BarcodeProcessor barcodeProcessor;
    private int isBarcodeEnabled;
    private int isProbillNumberActive;

    public AfterScanningAsyncTask(Context context) {
        this.context = context;
        mLocalFileManager = LocalFileManager.getInstance();
        mSoundPlayer = new SoundPlayer(context);
    }

    protected void onPreExecute() {
        super.onPreExecute();

        wifi = new WifiHelper(context.getApplicationContext());
        barcodeProcessor = new BarcodeProcessor(context);

        dialog = new ProgressDialog(context);
        dialog.setCancelable(false);
        dialog.setProgressStyle(android.R.style.Widget_ProgressBar_Small);
        dialog.setMessage("Please wait a moment\nConecting to Internet...");
        dialog.show();
    }

    @Override
    protected String doInBackground(String... strings) {
        String additionalEmail = strings[0];
        String validFaxNumber = strings[1];

        Log.i("Path", "-> " + mLocalFileManager.getAbsoulteFilePath());
        try {
//            String path = mLocalFileManager.getAbsoulteFilePath();

            File[] compatibleFiles = mLocalFileManager.getCompatibleFiles();
            Log.i("Files","->"+compatibleFiles.length);
            if (compatibleFiles.length == 1) {
                //Tell scanned pages if we have only one file........
                publishProgress(compatibleFiles[0].getAbsolutePath());
            }

            if(!wifi.hasActiveInternetConnection(context.getApplicationContext())) {
                try {
                    //check if connected!
                    while (!wifi.hasActiveInternetConnection(context.getApplicationContext())) {
                        //Wait to connect
                        Thread.sleep(500);
                        Log.i("Connect","...ing");
                    }
                } catch (Exception e) {
                    publishProgress("Alert");
                }

            }

            DatabaseManager dbManager = DatabaseManager.getInstance();

            Connection dbCon = dbManager.getConnection(context);
            ResultSet result = dbManager.executeSelecteQuery(dbCon,
                    "SELECT RR_mailCC, RR_mailBCC, RR_barcodeMode, RR_probillMode FROM RR_Settings WHERE RR_ID = "
                            + SharedPreferencesManager.getInstance(context).getScaniqRrid(),
                    context);
            try {
                while (result.next()) {
                    ccMail = result.getString("RR_mailCC");
                    bccMail = result.getString("RR_mailBCC");
                    isBarcodeEnabled = result.getInt("RR_barcodeMode");
                    isProbillNumberActive = result.getInt("RR_probillMode");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                publishProgress("Alert");
            }
            finally {
                dbManager.closeConnection(dbCon, context);
            }

            File[] filestoSend = mLocalFileManager.globalFileArray;
            int tempCount = mLocalFileManager.getFilesCount();
            ArrayList<String> barcodes = new ArrayList<>();
            String path = LocalFileManager.getInstance().getAbsoulteFilePath();
            for (File file: filestoSend){
                if (isBarcodeEnabled == 1) {
                    String barcode = barcodeProcessor.scanForBarcodes(file);
                    if (!barcode.equals("")) {
                        barcodes.add(barcode);
                        Log.i("BARCODE", "BARCODE -> " + barcode);
                        File newFile = new File(path, createFileName(barcode));
                        file.renameTo(newFile);
                    }
                } else if (isProbillNumberActive == 1) {
                    String probillNumber = SharedPreferencesManager.getInstance(context).getScanProbill();
                    File newFile = new File(path, createFileName(probillNumber));
                    file.renameTo(newFile);
                }
            }

            filestoSend = mLocalFileManager.getCompatibleFiles();

            sendToFTP();

            for (File tempFile : filestoSend) {

                publishProgress("Play Sound",""+tempCount--);
                storeDataIntoDatabase(tempFile,additionalEmail,validFaxNumber);

                String filePath = tempFile.getAbsolutePath();
                sendEmail(additionalEmail,filePath);

                if(!validFaxNumber.equals(""))
                {
                    sendFax(validFaxNumber,filePath);
                }
            }
            publishProgress("Play Sound",""+0);

        }
        catch (Exception e) {
            e.printStackTrace();
            publishProgress("Alert");

        }
        return null;
    }

    @Override
    protected void onPostExecute(String lenghtOfFile) {
        Log.i("Post","In");
        dialog.dismiss();
        mLocalFileManager.deleteFile();
    }

    @Override
    protected void onProgressUpdate(String... progress) {

        String updates = progress[0];
        int count = 0;
        if (progress.length > 1) {
             count = Integer.parseInt(progress[1]);
        }
        switch (updates)
        {
            case "Sending to server...":
                dialog.setMessage("Sending to server...");
                break;
            case "Sending email...":
                dialog.setMessage("Sending email...");
                break;
            case "Sending data to server...":
                dialog.setMessage("Sending data to server...");
                break;
            case "Play Sound":
                mSoundPlayer.playFilesSound(count);
                break;
            case "Alert":
                dialog.dismiss();
                AlertBoxBuilder.AlertBox(context,"Error","Some error appeared. Try scanning again please.");
                break;
            default :
                if (!updates.equals("")) {
                    //There is a file
                    //DETECT NUMBER OF PAGES AND CALL PAGES SOUND
                    PdfReader doc;
                    try {
                        doc = new PdfReader(new FileInputStream(updates));
                        final int pages = doc.getNumberOfPages();
                        mSoundPlayer.playPagesSound(pages);

                        Toast.makeText(context, pages + " page(s) scanned.", Toast.LENGTH_LONG).show();

                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
        }
    }


    private void storeDataIntoDatabase(File file,String ccEmail, String validFaxNumber) {
        publishProgress("Sending data to server...");
        Bundle bundle = new Bundle();

        bundle.putString("RR_ID",SharedPreferencesManager.getInstance(context).getScaniqRrid());//1.
        bundle.putString("scanDate",getCurrentDate());//2.
        bundle.putString("fileName",file.getName());//3.
        bundle.putString("fileType","PDF");//4.
        bundle.putInt("numberOfPages",getPagesFromFile(file));//5.
        bundle.putLong("fileSize",file.length());//6.
        bundle.putString("mailTo",SharedPreferencesManager.getInstance(context).getScaniqMailto());//7.
        bundle.putString("validFaxNUmber",(!validFaxNumber.equals(""))?validFaxNumber : "NA");//8.
        bundle.putString("tempCCEmail",(!ccEmail.equals(""))?ccEmail : "NA");//9.
        bundle.putString("Scanner_Serial_Num",(!serialNumber.equals(""))?serialNumber:"NA");//10.
        bundle.putDouble("Scan_Lat",SharedPreferencesManager.getInstance(context).getScanLat());//11.
        bundle.putDouble("Scan_Lon",SharedPreferencesManager.getInstance(context).getScanLon());//12.

        Connection con = DatabaseManager.getInstance().getConnection(context);
        DatabaseManager.getInstance().executeStoreScannedDataPreparedStatement(con,bundle,context);
    }

    private String getCurrentDate()
    {
//        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)).format(new Date());
    }

    private String createFileName(String barcode)
    {
        return barcode + "_" + (new SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.US)).format(new Date()) + ".pdf";
    }

    private int getPagesFromFile(File file){
        try {
            return new PdfReader(new FileInputStream(file)).getNumberOfPages();
        } catch (IOException e) {
            publishProgress("Alert");
            e.printStackTrace();
            return 0;
        }
    }

    private void sendToFTP() {
        //dialog.setMessage("Sending to server...");
        publishProgress("Sending to server...");
        File[] filestoSend = mLocalFileManager.getCompatibleFiles();

        for (File tempFile : filestoSend)
        {
            String filePath = tempFile.getAbsolutePath();


            FTPClient client = new FTPClient();
            FileInputStream fis = null;

            try {
                client.connect("web04.dexagon.net");
                client.login("scaniq", "snow721ftp");

                client.setFileType(FTP.BINARY_FILE_TYPE);
                client.setFileTransferMode(FTP.BINARY_FILE_TYPE);
                //
                // Create an InputStream of the file to be uploaded
                //

                String localname = new File(filePath).getName();

                String ftpFilePath = context.getString(R.string.ftp_folder) + SharedPreferencesManager.getInstance(context).getScaniqMd5();

                ftpCreateDirectoryTree(client, ftpFilePath);

                fis = new FileInputStream(filePath);

                // Store file to server
                client.storeFile(localname, fis);
                client.logout();
            } catch (Exception e) {
                mLocalFileManager.deleteFile();
                publishProgress("Alert");

//                AlertBoxBuilder.AlertBox(context,"Error","Storing File to Server Failed...\nPlease rescan the document!");
                e.printStackTrace();
            } finally {
                try {
                    if (fis != null) {
                        fis.close();
                    }
                    client.disconnect();
                } catch (IOException e) {
                    publishProgress("Alert");
                    e.printStackTrace();
                }
            }
        }

    }
    private static void ftpCreateDirectoryTree( FTPClient client, String ftpDir ) throws IOException {

        boolean dirExists;

        //tokenize the string and attempt to change into each directory level.  If you cannot, then start creating.
        String[] directories = ftpDir.split("/");
        //Log.i("directories"," -> "+ftpDir);
        for (String dir : directories ) {
          //  Log.i("directories", "" + dir.toString());

            if (!dir.isEmpty() ) {

                dirExists = client.changeWorkingDirectory(dir);

                if (!dirExists) {
                    if (!client.makeDirectory(dir)) {
                        throw new IOException("Unable to create remote directory '" + dir + "'.  error='" + client.getReplyString()+"'");
                    }
                    if (!client.changeWorkingDirectory(dir)) {
                        throw new IOException("Unable to change into newly created remote directory '" + dir + "'.  error='" + client.getReplyString()+"'");
                    }
                }
            }
        }
    }

    private void sendFax(String validFaxNumber, String filePath) {
  //      dialog.setMessage("Sending fax...");
        EmailSender sendScanEmail = new EmailSender("noreply@ez2scan.com","password01");

        SharedPreferencesManager mSharedPreferences = SharedPreferencesManager.getInstance(context);

        String from = "noreply@ez2scan.com";
        String subject = mSharedPreferences.getSCAN_USER_SERIAl();

        String to = validFaxNumber + "@srfax.com";
        Log.i("FAX", "to: " + to);
        String[] tos = to.split(",");

        sendScanEmail.setFrom(from);
        sendScanEmail.setTo(tos);
        sendScanEmail.setSubject(subject);
        try {
            sendScanEmail.addAttachment(filePath);
            sendScanEmail.send(true);
        } catch (Exception e) {
            publishProgress("Alert");
            e.printStackTrace();
        }
    }

    private void sendEmail(String additionalEmail,String filePath)  {
        publishProgress("Sending email...");
//        EmailSender sendScanEmail = new EmailSender("savanpatel39@gmail.com","savanpatel_39");
        EmailSender sendScanEmail = new EmailSender("noreply@ez2scan.com","password01");

        SharedPreferencesManager mSharedPreferences = SharedPreferencesManager.getInstance(context);

        ArrayList<String> to = new ArrayList<>();

        to.add(mSharedPreferences.getScaniqMailto());

        if(!additionalEmail.equals(""))
        {
            to.add(additionalEmail);
        }

        if (!ccMail.equals("")) {
            sendScanEmail.setCC(ccMail);
        }

        if (!bccMail.equals("")) {
            sendScanEmail.setBCC(bccMail);
        }

        String from = "noreply@ez2scan.com";
        String subject = mSharedPreferences.getSCAN_USER_SERIAl();

        String messageBody = "<html><body>Click <a href=http://scaniq.secureserverdot.com/scans/"+mSharedPreferences.getScaniqMd5()
                + "/"
                + filePath.substring(filePath.lastIndexOf("/")+1)
                + ">here</a> to see the scanned document courtesy of ScanIQ.";

        double lat = mSharedPreferences.getScanLat();
        double lon = mSharedPreferences.getScanLon();

        Log.i("LatLon"," -> Lat: "+lat+"lon: "+lon);

        if( lat!=0.0 || lon!=0.0 ){
            messageBody +=	"<br>See <a href='" + "https://maps.google.com/maps?q=" + lat + "+" + lon + "' >Location</a> of scanned document. </body></html>";
        }

        sendScanEmail.setFrom(from);
        sendScanEmail.setTo((to.toArray(new String[to.size()])));
//        Log.i("Task", "to length" + to.length);

        sendScanEmail.setSubject(subject);
        sendScanEmail.setBody(messageBody);

        try {
            sendScanEmail.addAttachment(filePath);
        } catch (Exception e) {
            publishProgress("Alert");
            e.printStackTrace();
        }

        try
        {
            sendScanEmail.send(false);
        }
        catch (Exception e)
        {
            sendScanEmail.setPort("25");
            Log.i("Exception","-> "+e.getMessage());
            e.printStackTrace();
            try
            {
                sendScanEmail.send(false);
            }
            catch (Exception e1)
            {
                e1.printStackTrace();
                mLocalFileManager.deleteFile();
                publishProgress("Alert");
            }
        }
    }
}