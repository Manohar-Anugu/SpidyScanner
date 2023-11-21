package cz.adaptech.tesseract4android.sample.ui.main;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import cz.adaptech.tesseract4android.sample.Assets;
import cz.adaptech.tesseract4android.sample.Config;
import cz.adaptech.tesseract4android.sample.databinding.FragmentMainBinding;

public class MainFragment extends Fragment {

    private FragmentMainBinding binding;

    private MainViewModel viewModel;

    private File selectedFile;

    public static MainFragment newInstance() {
        return new MainFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        // Copy sample image and language data to storage
        Assets.extractAssets(requireContext());

        // If tessaract is not initialized then initialize it
        if (!viewModel.isInitialized()) {
            String dataPath = Assets.getTessDataPath(requireContext());
            viewModel.initTesseract(dataPath, Config.TESS_LANG, Config.TESS_ENGINE);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        //setting the UI view for the screen
        binding = FragmentMainBinding.inflate(inflater, container, false);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Registers a photo picker activity launcher in single-select mode.
        //this callback will be triggered
        ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
                registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                    // Callback is invoked after the user selects a media item or closes the
                    // photo picker.
                    if (uri != null) {
                        Log.d("PhotoPicker", "Selected URI: " + uri);

                        //Start- Saving the phone file to our app storage

                        ParcelFileDescriptor parcelFileDescriptor = null;
                        try {
                            parcelFileDescriptor = getActivity().getContentResolver().openFileDescriptor(uri, "r");
                        } catch (FileNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                        FileDescriptor fileDescriptor  = parcelFileDescriptor.getFileDescriptor();
                        FileInputStream input = new FileInputStream(fileDescriptor);
                        Path path  = Paths.get(String.valueOf(getActivity().getFilesDir()), "test.jpg");

                        try {
                            Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
                            input.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }

                        //END- Saving the phone file to our app storage

                        selectedFile = path.toFile();
                        //Setting the selected image to the Cropping Image view
                        binding.cropImageView.setImageUriAsync(uri);

                    } else {
                        Log.d("PhotoPicker", "No media selected");
                    }
                });


        //This is called when ever user clicks on the pick image button
        binding.btnPickImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {


            // Launch the photo picker and let the user choose only images.
                pickMedia.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());

            }
        });


        //If selected image is in wrong orientation then user can rotate the image using this button
        binding.ivRotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                binding.cropImageView.rotateImage(90);
            }
        });


        //This will trigger when ever user clicks on the Camera button
        binding.btnCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                dispatchTakePictureIntent();

            }
        });


        // When ever user clicks on the start button this method will be triggered
        binding.start.setOnClickListener(v -> {

            //Getting the cropped part of the Image
            File file = saveCroppedImage();

            if (file.exists()) {
                Boolean useMlModel = binding.switchMlModel.isChecked();
                if (!useMlModel) {
                    viewModel.recognizeImage(file);
                }
                detectTextBoxMLKit(useMlModel);
            }else {
                Toast.makeText(getActivity(),"Please select a File",Toast.LENGTH_LONG).show();
            }
        });
        binding.stop.setOnClickListener(v -> {
            viewModel.stop();
        });
        binding.text.setMovementMethod(new ScrollingMovementMethod());

        viewModel.getProcessing().observe(getViewLifecycleOwner(), processing -> {
            binding.start.setEnabled(!processing);
            binding.stop.setEnabled(processing);
        });
        viewModel.getProgress().observe(getViewLifecycleOwner(), progress -> {
            binding.status.setText(progress);
        });
        viewModel.getResult().observe(getViewLifecycleOwner(), result -> {
            binding.text.setText(result);
        });
    }

    /**
     * This method is to Save the cropped part of the image from the original Image
     * @return
     */
    private File saveCroppedImage(){
        File f = new File(getActivity().getCacheDir(), "test.jpg");
        try {
            if (f.exists()){
                f.delete();
            }
            f.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

//Convert bitmap to byte array
        Bitmap bitmap = binding.cropImageView.getCroppedImage();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 0 /*ignored for PNG*/, bos);
        byte[] bitmapdata = bos.toByteArray();

//write the bytes in file
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(bitmapdata);
            fos.flush();
            fos.close();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return f;

    }

    /**
     *  Launch an intent to take picture from camera and save in to specified path
     */
    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                // Error occurred while creating the File
            }
            // Continue only if the File was successfully created
            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(getContext(),
                        "cz.adaptech.tesseract4android.sample",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                someActivityResultLauncher.launch(takePictureIntent);
            }
//        }
    }


    //Call back for getting the result from camera intent
    ActivityResultLauncher<Intent> someActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    // If the camera is successful and image is saved the result code will be OK
                    if (result.getResultCode() == Activity.RESULT_OK) {

                        FileInputStream input = null;
                        selectedFile = new File(currentPhotoPath);
                        try {
                            // delete already existing image from  path
                            input = new FileInputStream(selectedFile);
                            Path path  = Paths.get(String.valueOf(getActivity().getFilesDir()), "test.jpg");
                            File file = path.toFile();
                            file.delete();

                            if (!file.exists()){
                                file.createNewFile();
                            }
                            //saving the new camera image to the path
                            copyFileFromSourceToDestination(selectedFile,file);

                            selectedFile = file;
                            //setting the image to cropped image view
                            binding.cropImageView.setImageUriAsync(Uri.fromFile(selectedFile));

                        } catch (FileNotFoundException e) {
                            throw new RuntimeException(e);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }

                    }
                }
            });


    /**
     * This method is to draw the bounding boxes for the text inside the image using google mlkit model
     * @param detectText if true then text detection is done using the google mlkit model else if false then text detection is
     *                   done using Tesseract model
     */
    public void detectTextBoxMLKit(Boolean detectText){
        if (detectText){
            binding.status.setText("In Progress");
        }
        TextRecognizer recognizer =
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        InputImage image = null;
        //Getting the cropped image for processing
        Bitmap bitmap = binding.cropImageView.getCroppedImage();
        //start time is used for calculating the time taken for detection of the text
        Long startTime = System.currentTimeMillis();
        try {
//            image = InputImage.fromFilePath(getContext(), uri);

            image = InputImage.fromBitmap(bitmap, 0);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }


/// processing the image using the google mlkit model
        Task<Text> result =
                recognizer.process(image)
                        .addOnSuccessListener(new OnSuccessListener<Text>() {
                            @Override
                            public void onSuccess(Text visionText) {
                                // Task completed successfully
                                // This method is called once the image is successfully processed, we get the result in the vision text

                                //getting list of text boxes from the result
                               List<Text.TextBlock> textBlocks = visionText.getTextBlocks();

                               //calculating total time taken for processing the image
                               Long totalTime = System.currentTimeMillis() - startTime;
                                Double seconds = totalTime/1000.0;
                                //if use ML model checkbox is checked then the detected text is set using the ML result
                                if (detectText) {
                                    binding.status.setText("Completed in " + seconds + " sec");
                                }
                                //drawing the bounding boxes around the detected text in the image
                                drawBoundingBoxes(bitmap,textBlocks,detectText);
                            }
                        })
                        .addOnFailureListener(
                                new OnFailureListener() {
                                    @Override
                                    public void onFailure(@NonNull Exception e) {
                                        // This method is called if the image is unable to be processed by mlkit model
                                        // ...
                                    }
                                });

    }


    /**
     * This method is for drawing the bounding boxes around the detected text in the image
     * @param bitmap the image around in which the bounding boxes need to drawn
     * @param textBlocks the coordinates for the bounding boxes
     * @param detectText if true then text detection is done using the google mlkit model else if false then text detection is
     *      *                   done using Tesseract model
     */
    public void drawBoundingBoxes(Bitmap bitmap,List<Text.TextBlock> textBlocks,boolean detectText){

        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
//        Canvas canvas = new Canvas(workingBitmap);
        Canvas canvas = new Canvas(bitmap);

        Paint paint=new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.RED);
        StringBuilder detectedText = new StringBuilder();
        for (Text.TextBlock textBlock :textBlocks){

            detectedText.append(textBlock.getText());
            detectedText.append(System.lineSeparator());
            if (textBlock.getBoundingBox()!=null) {
                canvas.drawRect(textBlock.getBoundingBox(), paint);
            }

        }
        binding.cropImageView.setImageBitmap(bitmap);
        if (detectText){
            binding.text.setText(detectedText);
        }
    }

    /**
     * This method is to Copy the image from source to destination
     * @param src The source file of the image
     * @param dst The destination file of the image
     * @throws IOException
     */
    public static void copyFileFromSourceToDestination(File src, File dst) throws IOException
    {

        FileChannel inChannel = new FileInputStream(src).getChannel();
        FileChannel outChannel = new FileOutputStream(dst).getChannel();
        try
        {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        }
        finally
        {
            if (inChannel != null)
                inChannel.close();
            if (outChannel != null)
                outChannel.close();
        }
    }

    String currentPhotoPath;

    /**
     * This Method is to Create a unique path for saving the result from camera
     * @return [File]- File with unique path
     * @throws IOException
     */
    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );

        // Save a file: path for use with ACTION_VIEW intents
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }


}