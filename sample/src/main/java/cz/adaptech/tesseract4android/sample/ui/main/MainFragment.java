package cz.adaptech.tesseract4android.sample.ui.main;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
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

        if (!viewModel.isInitialized()) {
            String dataPath = Assets.getTessDataPath(requireContext());
            viewModel.initTesseract(dataPath, Config.TESS_LANG, Config.TESS_ENGINE);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMainBinding.inflate(inflater, container, false);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Registers a photo picker activity launcher in single-select mode.
        ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
                registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                    // Callback is invoked after the user selects a media item or closes the
                    // photo picker.
                    if (uri != null) {
                        Log.d("PhotoPicker", "Selected URI: " + uri);

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

                        selectedFile = path.toFile();
                        binding.cropImageView.setImageUriAsync(uri);

                    } else {
                        Log.d("PhotoPicker", "No media selected");
                    }
                });

        binding.btnPickImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {


            // Launch the photo picker and let the user choose only images.
                pickMedia.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());

            }
        });

        binding.ivRotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                binding.cropImageView.rotateImage(90);
            }
        });
        binding.btnCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

//                if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
//                        == PackageManager.PERMISSION_DENIED){
//                    ActivityCompat.requestPermissions(getActivity(), new String[] {Manifest.permission.CAMERA}, 500);
//                    return;
//                }
//                if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
//                        == PackageManager.PERMISSION_DENIED){
//                    ActivityCompat.requestPermissions(getActivity(), new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 500);
//                    return;
//                }
                dispatchTakePictureIntent();

            }
        });

//        binding.image.setImageBitmap(Assets.getImageBitmap(requireContext()));
        binding.start.setOnClickListener(v -> {
//            File imageFile = Assets.getImageFile(requireContext());

            File file = saveCroppedImage();

//            Path path  = Paths.get(String.valueOf(getActivity().getFilesDir()), "test.jpg");
//            File file = path.toFile();
            if (file.exists()) {
                viewModel.recognizeImage(file);
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

    static final int REQUEST_IMAGE_CAPTURE = 1;

//    private void dispatchTakePictureIntent() {
//        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
//        try {
//            someActivityResultLauncher.launch(takePictureIntent);
//        } catch (ActivityNotFoundException e) {
//            // display error state to the user
//        }
//    }

    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ensure that there's a camera activity to handle the intent
//        if (takePictureIntent.resolveActivity(getActivity().getPackageManager()) != null) {
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


    ActivityResultLauncher<Intent> someActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        // There are no request codes
//                        binding.image.setImageURI(null);
                        FileInputStream input = null;
                        selectedFile = new File(currentPhotoPath);
                        try {
                            input = new FileInputStream(selectedFile);
                            Path path  = Paths.get(String.valueOf(getActivity().getFilesDir()), "test.jpg");
                            File file = path.toFile();
                            file.delete();

                            if (!file.exists()){
                                file.createNewFile();
                            }
                            rotateAndCopyFile(selectedFile,file);
//                            try {
//                                Thread.sleep(500);
//                            } catch (InterruptedException e) {
//                                throw new RuntimeException(e);
//                            }
                            selectedFile = file;
                            binding.cropImageView.setImageUriAsync(Uri.fromFile(selectedFile));

//                            Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
//                            input.close();
//                            selectedFile = path.toFile();
                        } catch (FileNotFoundException e) {
                            throw new RuntimeException(e);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }

                    }
                }
            });


    public static Bitmap rotateImage(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(),
                matrix, true);
    }

    public static void rotateAndCopyFile(File src, File dst) throws IOException
    {
/*

        Bitmap bitmap = BitmapFactory.decodeFile(src.getAbsolutePath());
        ExifInterface ei = new ExifInterface(src.getAbsolutePath());
        int orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED);

        Bitmap rotatedBitmap = null;
        switch(orientation) {

            case ExifInterface.ORIENTATION_ROTATE_90:
                rotatedBitmap = rotateImage(bitmap, 90);
                break;

            case ExifInterface.ORIENTATION_ROTATE_180:
                rotatedBitmap = rotateImage(bitmap, 180);
                break;

            case ExifInterface.ORIENTATION_ROTATE_270:
                rotatedBitmap = rotateImage(bitmap, 270);
                break;

            case ExifInterface.ORIENTATION_NORMAL:
            default:
                rotatedBitmap = bitmap;
        }


        try (FileOutputStream out = new FileOutputStream(dst)) {
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out); // bmp is your Bitmap instance
            // PNG is a lossless format, the compression factor (100) is ignored
        } catch (IOException e) {
            e.printStackTrace();
        }


*/



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