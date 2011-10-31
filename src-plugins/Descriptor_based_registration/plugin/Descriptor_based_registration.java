package plugin;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.models.AffineModel2D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.RigidModel2D;
import mpicbg.models.RigidModel3D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.segmentation.InteractiveDoG;
import fiji.plugin.Bead_Registration;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.MultiLineLabel;
import ij.plugin.PlugIn;
import ij.process.ImageConverter;
import ij.process.StackConverter;

public class Descriptor_based_registration implements PlugIn 
{
	final private String myURL = "http://fly.mpi-cbg.de/preibisch";
	final private String paperURL = "http://www.nature.com/nmeth/journal/v7/n6/full/nmeth0610-418.html";

	public static int defaultImg1 = 0;
	public static int defaultImg2 = 1;

	//@Override
	public void run(String arg0) 
	{		
		// get list of image stacks
		final int[] idList = WindowManager.getIDList();		

		if ( idList == null || idList.length < 2 )
		{
			IJ.error( "You need at least two open images." );
			return;
		}

		final String[] imgList = new String[ idList.length ];
		for ( int i = 0; i < idList.length; ++i )
			imgList[ i ] = WindowManager.getImage(idList[i]).getTitle();

		/**
		 * The first dialog for choosing the images
		 */
		final GenericDialog gd = new GenericDialog( "Descriptor based registration" );
	
		gd.addChoice("First_image (reference)", imgList, imgList[ defaultImg1 ] );
		gd.addChoice("Second_image (to register)", imgList, imgList[ defaultImg2 ] );
		gd.addMessage( "Warning: if images are of RGB or 8-bit they will be converted to hyperstacks.");
		gd.addMessage( "Please note that the SPIM Registration is based on a publication.\n" +
					   "If you use it successfully for your research please be so kind to cite our work:\n" +
					   "Preibisch et al., Nature Methods (2010), 7(6):418-419\n" );

		MultiLineLabel text =  (MultiLineLabel) gd.getMessage();
		Bead_Registration.addHyperLinkListener( text, paperURL );

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return;
		
		ImagePlus imp1 = WindowManager.getImage( idList[ defaultImg1 = gd.getNextChoiceIndex() ] );		
		ImagePlus imp2 = WindowManager.getImage( idList[ defaultImg2 = gd.getNextChoiceIndex() ] );		

		// if one of the images is rgb or 8-bit color convert them to hyperstack
		imp1 = convertToHyperStack( imp1 );
		imp2 = convertToHyperStack( imp2 );
		
		// test if the images are compatible
		String error = Stitching_Pairwise.testRegistrationCompatibility( imp1, imp2 );

		if ( error != null )
		{
			IJ.log( error );
			return;
		}
				
		// get the parameters
		final int dimensionality;
		
		if ( imp1.getNSlices() > 1 )
			dimensionality = 3;
		else
			dimensionality = 2;
		
		// open a second dialog and query the other parameters
		getParameters( imp1, imp2, dimensionality );
	}
	
	public String[] transformationModels2d = new String[] { "Translation (2d)", "Rigid (2d)", "Similarity (2d)", "Affine (2d)", "Homography (2d)" };
	public String[] transformationModels3d = new String[] { "Translation (3d)", "Rigid (3d)", "Affine (3d)" };
	public static int defaultTransformationModel = 1;
	
	public static String[] detectionBrightness = { "Very low", "Low", "Medium", "Strong", "Advanced ...", "Interactive ..." };
	public static int defaultDetectionBrightness = 1;
	public static double defaultSigma = 1.8;
	public static double defaultThreshold = 0.03;
	
	public static String[] detectionRadius = { "0.5 px", "1 px", "2 px", "3 px", "4 px", "5 px", "6 px", "7 px", "8 px", "9 px", "10 px", "Advanced ...", "Interactive ..." };
	public static int defaultDetectionRadius = 1;
	
	public static String[] detectionTypes = { "Maxima only", "Minima only", "Minima & Maxima", "Interactive ..." };
	public static int defaultDetectionType = 0;
	
	public static int defaultNumNeighbors = 1;
	public static int defaultRedundancy = 1;
	public static double defaultSignificance = 3;
	public static double defaultRansacThreshold = 5;
	public static int defaultChannel1 = 1;
	public static int defaultChannel2 = 1;
	
	/**
	 * Ask for all other required parameters ..
	 * 
	 * @param dimensionality
	 */
	protected DescriptorParameters getParameters( final ImagePlus imp1, final ImagePlus imp2, final int dimensionality )
	{
		final String[] transformationModel = dimensionality == 2 ? transformationModels2d : transformationModels3d;
	
		// check if default selection of transformation model holds
		if ( defaultTransformationModel >= transformationModel.length )
			defaultTransformationModel = 1;
		
		// one of them is by default interactive, then all are interactive
		if ( defaultDetectionBrightness == detectionBrightness.length - 1 || 
			 defaultDetectionRadius == detectionRadius.length - 1 ||
			 defaultDetectionType == detectionTypes.length - 1 )
		{
			defaultDetectionBrightness = detectionBrightness.length - 1; 
			defaultDetectionRadius = detectionRadius.length - 1;
			defaultDetectionType = detectionTypes.length - 1;
		}
		
		final GenericDialog gd = new GenericDialog( dimensionality + "-dimensional descriptor based registration" );			
		
		gd.addChoice( "Brightness of detections", detectionBrightness, detectionBrightness[ defaultDetectionBrightness ] );
		gd.addChoice( "Approximate radius of detections", detectionRadius, detectionRadius[ defaultDetectionRadius ] );
		gd.addChoice( "Type of detections", detectionTypes, detectionTypes[ defaultDetectionType ] );
		
		gd.addChoice( "Transformation model", transformationModel, transformationModel[ defaultTransformationModel ] );
		
		if ( dimensionality == 2 )
		{
			if ( defaultNumNeighbors < 2 )
				defaultNumNeighbors = 2;
			
			gd.addSlider( "Number of neighbors for the descriptors", 2, 10, defaultNumNeighbors );
		}
		else
		{
			if ( defaultNumNeighbors < 3 )
				defaultNumNeighbors = 3;
			
			gd.addSlider( "Number of neighbors for the descriptors", 3, 10, defaultNumNeighbors );
		}
		
		gd.addSlider( "Redundancy for descriptor matching", 0, 10, defaultRedundancy );		
		gd.addSlider( "Significance required for a descriptor match", 1.0, 10.0, defaultSignificance );
		gd.addSlider( "Allowed error for RANSAC (px)", 0.5, 20.0, defaultRansacThreshold );

		final int numChannels1 = imp1.getNChannels();
		final int numChannels2 = imp2.getNChannels();
		
		if ( defaultChannel1 > numChannels1 )
			defaultChannel1 = 1;
		if ( defaultChannel2 > numChannels2 )
			defaultChannel2 = 1;
		
		gd.addSlider( "Choose registration channel for image 1" , 1, numChannels1, defaultChannel1 );
		gd.addSlider( "Choose registration channel for image 2" , 1, numChannels2, defaultChannel2 );

		gd.addMessage("");
		gd.addMessage("This Plugin is developed by Stephan Preibisch\n" + myURL);

		MultiLineLabel text = (MultiLineLabel) gd.getMessage();
		Bead_Registration.addHyperLinkListener(text, myURL);

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;
		
		final DescriptorParameters params = new DescriptorParameters();
		
		final int detectionBrightnessIndex = gd.getNextChoiceIndex();
		final int detectionRadiusIndex = gd.getNextChoiceIndex();
		final int detectionTypeIndex = gd.getNextChoiceIndex();
		final int transformationModelIndex = gd.getNextChoiceIndex();
		final int numNeighbors = (int)Math.round( gd.getNextNumber() );
		final int redundancy = (int)Math.round( gd.getNextNumber() );
		final double significance = gd.getNextNumber();
		final double ransacThreshold = gd.getNextNumber();
		final int channel1 = (int)Math.round( gd.getNextNumber() );
		final int channel2 = (int)Math.round( gd.getNextNumber() );
		
		// update static values for next call
		defaultDetectionBrightness = detectionBrightnessIndex;
		defaultDetectionRadius = detectionRadiusIndex;
		defaultDetectionType = detectionTypeIndex;
		defaultTransformationModel = transformationModelIndex;
		defaultNumNeighbors = numNeighbors;
		defaultRedundancy = redundancy;
		defaultSignificance = significance;
		defaultRansacThreshold = ransacThreshold;
		defaultChannel1 = channel1;
		defaultChannel2 = channel2;
		
		// one of them is by default interactive, then all are interactive
		if ( detectionBrightnessIndex == detectionBrightness.length - 1 || 
			 detectionRadiusIndex == detectionRadius.length - 1 ||
			 detectionTypeIndex == detectionTypes.length - 1 )
		{
			// query parameters interactively
			final double[] values = new double[]{ defaultSigma, defaultThreshold };
			final InteractiveDoG idog = getInteractiveDoGParameters( imp1, channel1, values, 20 );
			
			params.sigma = values[ 0 ];
			params.threshold = values[ 1 ];
			params.lookForMaxima = idog.getLookForMaxima();
			params.lookForMinima = idog.getLookForMinima();
		}
		else 
		{
			if ( detectionBrightnessIndex == detectionBrightness.length - 1 || detectionRadiusIndex == detectionRadius.length - 1 )
			{
				// ask for the dog parameters
				final double[] values = getAdvancedDoGParameters( defaultSigma, defaultThreshold );
				params.sigma = values[ 0 ];
				params.threshold = values[ 1 ];				
			}
			else
			{
				if ( detectionBrightnessIndex == 0 )
					params.threshold = 0.001;			
				else if ( detectionBrightnessIndex == 1 )
					params.threshold = 0.008;			
				else if ( detectionBrightnessIndex == 2 )
					params.threshold = 0.03;			
				else if ( detectionBrightnessIndex == 3 )
					params.threshold = 0.1;
	
				if ( detectionRadiusIndex == 0 )
					params.sigma = 1;
				else 
					params.sigma = detectionRadiusIndex * 2;
			}
			
			if ( detectionTypeIndex == 2 )
			{
				params.lookForMaxima = true;
				params.lookForMinima = true;
			}
			else if ( detectionTypeIndex == 1 )
			{
				params.lookForMinima = true;
				params.lookForMaxima = false;
			}
			else
			{
				params.lookForMinima = false;
				params.lookForMaxima = true;				
			}			
		}
		// set the new default values
		defaultSigma = params.sigma;
		defaultThreshold = params.threshold;
		
		if ( params.lookForMaxima && params.lookForMinima )
			defaultDetectionType = 2;
		else if ( params.lookForMinima )
			defaultDetectionType = 1;
		else
			defaultDetectionType = 0;
	
		// instantiate model
		if ( dimensionality == 2 )
		{
			switch ( transformationModelIndex ) 
			{
				case 0:
					params.model = new TranslationModel2D();
					break;
				case 1:
					params.model = new RigidModel2D();
					break;
				case 2:
					params.model = new SimilarityModel2D();
					break;
				case 3:
					params.model = new AffineModel2D();
					break;
				case 4:
					params.model = new HomographyModel2D();
					break;
				default:
					params.model = new RigidModel2D();
					break;
			}
		}
		else
		{
			switch ( transformationModelIndex ) 
			{
				case 0:
					params.model = new TranslationModel3D();
					break;
				case 1:
					params.model = new RigidModel3D();
					break;
				case 2:
					params.model = new AffineModel3D();
					break;
				default:
					params.model = new RigidModel3D();
					break;
			}			
		}
		
		// other parameters
		params.numNeighbors = numNeighbors;
		params.redundancy = redundancy;
		params.significance = significance;
		params.ransacThreshold = ransacThreshold;
		
		return params;
	}
	
	protected double[] getAdvancedDoGParameters( final double defaultSigma, final double defaultThreshold )
	{
		final GenericDialog gd = new GenericDialog( "Select Difference-of-Gaussian parameters" );
		
		gd.addNumericField( "Detection_sigma (approx. radius)", defaultSigma, 4 );
		gd.addNumericField( "Threshold", defaultThreshold, 4 );
		
		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;
		else
			return new double[]{ gd.getNextNumber(), gd.getNextNumber() };
	}
	
	/**
	 * Can be called with values[ 3 ], i.e. [initialsigma, sigma2, threshold] or
	 * values[ 2 ], i.e. [initialsigma, threshold]
	 * 
	 * The results are stored in the same array.
	 * If called with values[ 2 ], sigma2 changing will be disabled
	 * 
	 * @param text - the text which is shown when asking for the file
	 * @param values - the initial values and also contains the result
	 * @param sigmaMax - the maximal sigma allowed by the interactive app
	 * @return {@link InteractiveDoG} - the instance for querying additional parameters
	 */
	public static InteractiveDoG getInteractiveDoGParameters( final ImagePlus imp, final int channel, final double values[], final float sigmaMax )
	{
		if ( imp.isHyperStack() )
			IJ.log( "is hyperstack" );
		
		imp.setSlice( imp.getStackSize() / 2 );	
		imp.setRoi( 0, 0, imp.getWidth()/3, imp.getHeight()/3 );		
		
		final InteractiveDoG idog = new InteractiveDoG( imp, channel - 1 );
		idog.setSigmaMax( sigmaMax );
		
		if ( values.length == 2 )
		{
			idog.setSigma2isAdjustable( false );
			idog.setInitialSigma( (float)values[ 0 ] );
			idog.setThreshold( (float)values[ 1 ] );
		}
		else
		{
			idog.setInitialSigma( (float)values[ 0 ] );
			idog.setThreshold( (float)values[ 2 ] );			
		}
		
		idog.run( null );
		
		while ( !idog.isFinished() )
			SimpleMultiThreading.threadWait( 100 );
		
		if ( values.length == 2)
		{
			values[ 0 ] = idog.getInitialSigma();
			values[ 1 ] = idog.getThreshold();
		}
		else
		{
			values[ 0 ] = idog.getInitialSigma();
			values[ 1 ] = idog.getSigma2();						
			values[ 2 ] = idog.getThreshold();
		}
		
		return idog;
	}
	
	/**
	 * Converts this image to Hyperstack if it is RGB or 8-bit color
	 * 
	 * @param imp - Inputimage
	 * @return the output image, might be the same
	 */
	public static ImagePlus convertToHyperStack( ImagePlus imp )
	{
		// first 8-bit color to RGB, directly to Hyperstack is not supported
		if ( imp.getType() == ImagePlus.COLOR_256 )
		{
			if ( imp.getStackSize() > 1 )
				new StackConverter( imp ).convertToRGB();
			else
				new ImageConverter( imp ).convertToRGB();
		}
		
		// now convert to hyperstack, this creates a new imageplus
		if ( imp.getType() == ImagePlus.COLOR_RGB )
			imp = new CompositeConverter2().makeComposite( imp );
		
		return imp;
	}

}